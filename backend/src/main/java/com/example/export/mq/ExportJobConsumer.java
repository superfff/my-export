package com.example.export.mq;

import com.example.export.service.ExportJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 导出任务消费者：从 export.job.queue 取消息，竞争领取（PENDING→RUNNING + 记一次 attempt），
 * 抢到后在同一消息处理内"真实执行"（生成 excel，落 SUCCESS/FAILED 终态），之后才 basicAck。
 *
 * <p>时序（manual ack，prefetch=1，concurrency=2，每个消费者同一时刻至多 1 条在途）：
 * <pre>
 * 取到消息
 *   └─ 写 x-trace-id 进 MDC
 *   └─ [有界重试 5 次] 反序列化 ExportEventMessage → claim(eventId, jobId)   // 只重试"短事务"段
 *        ├─ claim=false（未抢到/已终态/重复投递）→ basicAck 丢弃，结束
 *        └─ claim=true → executeExport(jobId)
 *              ├─ 成功：job/attempt=SUCCESS（终态已提交）→ basicAck
 *              └─ 业务/查询/写批失败：executeExport 内部已落 FAILED 终态 → basicAck
 *        └─ 重试耗尽仍抛（解析失败 / claim 一直 DB 异常）→ basicNack(requeue=false) → export.job.dlq
 *   └─ 清 MDC
 * </pre>
 *
 * <p>ack 语义：claim=false、executeExport 正常返回（内部已落 SUCCESS 或 FAILED）都 basicAck；
 * 只有 executeExport 连终态都落不了而抛出、或解析/claim 重试耗尽，才 basicNack(requeue=false)。
 *
 * <p>已知崩溃窗口（不做自动恢复，留后续阶段）：claim 提交后、executeExport 终态落定前崩溃 →
 * 消息重投 → 新消费者 claim 0 行 → ack 丢弃 → job 停在 RUNNING。
 *
 * <p>失败处置（manual ack 的约束）：acknowledge-mode=manual 下容器不会替消费者 reject，容器级重试耗尽后
 * 消息会悬在未确认态、无法进死信。故重试与最终处置都在方法内完成：
 * 用与容器同一 RetryTemplate(maxAttempts=5 + 指数退避) 对"反序列化 + claim"做有界重试，
 * 耗尽仍失败 → 显式 basicNack(requeue=false) → 主队列 DLX → export.job.dlq。
 */
@Component
public class ExportJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExportJobConsumer.class);

    private static final String TRACE_KEY = "traceId";

    /** 短事务段（反序列化 + claim）重试模板：5 次尝试 = 1 次 + 4 次重试，指数退避。 */
    private final RetryTemplate retryTemplate = RetryTemplate.builder()
            .maxAttempts(5)
            .exponentialBackoff(1000, 2.0, 8000)
            .build();

    private final ExportJobService exportJobService;
    private final ObjectMapper objectMapper;

    public ExportJobConsumer(ExportJobService exportJobService, ObjectMapper objectMapper) {
        this.exportJobService = exportJobService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitNames.EXPORT_JOB_QUEUE)
    public void onMessage(Message message, Channel channel) {
        Object rawTrace = message.getMessageProperties().getHeader(RabbitNames.TRACE_HEADER);
        String traceId = rawTrace instanceof String s && !s.isBlank() ? s : UUID.randomUUID().toString();
        MDC.put(TRACE_KEY, traceId);
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            // 1. 反序列化 + claim 放同一重试内（"短事务"段）：坏 payload 每轮同因失败、DB 抖动可安全重入
            //    （重复执行由抢占 CAS 幂等兜底），耗尽仍失败才进死信。
            ExportEventMessage[] eventBox = new ExportEventMessage[1];
            boolean claimed = retryTemplate.execute(context -> {
                ExportEventMessage event = objectMapper.readValue(message.getBody(), ExportEventMessage.class);
                eventBox[0] = event;
                return exportJobService.claim(event.eventId(), event.jobId());
            });
            ExportEventMessage event = eventBox[0];

            if (claimed) {
                // 2. 真实执行（只跑一次，不放重试）：执行器内部自保证终态（SUCCESS/FAILED 两处落库）。
                //    正常返回即终态已提交 → ack；只有连终态都落不了（DB 完全不可用）才会抛出到下方 catch。
                exportJobService.executeExport(event.jobId());
                channel.basicAck(deliveryTag, false);
                log.info("消费者执行完成并 ack: traceId={}, jobId={}", traceId, event.jobId());
            } else {
                // 3. 未抢占到（重复投递 / 已非 PENDING / 历史终态）→ 幂等丢弃，不留执行痕迹
                channel.basicAck(deliveryTag, false);
                log.info("消费者未抢占到(重复投递/已非PENDING), 幂等丢弃: traceId={}, jobId={}", traceId, event.jobId());
            }
        } catch (Exception e) {
            // manual ack 下容器不代劳：显式"拒绝且不 requeue"，触发主队列 DLX → export.job.dlq
            log.error("消费者处理失败(重试 5 次仍失败或终态落不了), 拒绝进死信: traceId={}, reason={}",
                    traceId, e.getMessage(), e);
            rejectToDlq(channel, deliveryTag);
        } finally {
            MDC.remove(TRACE_KEY);
        }
    }

    private void rejectToDlq(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (Exception e) {
            // 通道已断开等：消息保持未 ack，重连后重投（at-least-once），由抢占幂等兜底
            log.warn("拒绝进死信失败(通道可能已断开), 消息将随重连重投: deliveryTag={}", deliveryTag);
        }
    }
}
