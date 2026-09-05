package com.example.export.outbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.export.entity.OutboxEvent;
import com.example.export.mapper.OutboxEventMapper;
import com.example.export.mq.ExportEventMessage;
import com.example.export.mq.RabbitNames;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * outbox 投递者（生产者）：周期扫描 outbox_events 中未发布行（published_at IS NULL），投递到 RabbitMQ；
 * 收到 broker confirm 且无路由 return 才回写 published_at=NOW()（同一 UPDATE，再次校验
 * published_at IS NULL 防并发重复回写）。发布与否以 published_at IS NULL 为唯一判据，无 status 列。
 *
 * 可靠性要点：
 * - MQ 不可用 / 投递未确认 / 路由 return → 不改任何数据库状态，行保持未发布，下轮自动补投；
 * - 允许重复投递：重复发布由消费者"抢占 0 行 → ack 丢弃"幂等兜底，故无需分布式锁（单实例假设）。
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private static final String TRACE_KEY = "traceId";
    private static final int BATCH_SIZE = 100;

    private final OutboxEventMapper outboxEventMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public OutboxDispatcher(OutboxEventMapper outboxEventMapper,
                            RabbitTemplate rabbitTemplate,
                            ObjectMapper objectMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    /** 约每 5 秒扫描一轮（fixedDelay 防轮次重叠；initialDelay 等待 Rabbit 连接就绪）。 */
    @Scheduled(fixedDelay = 5000, initialDelay = 2000)
    public void dispatch() {
        List<OutboxEvent> pending = outboxEventMapper.selectList(
                new LambdaQueryWrapper<OutboxEvent>()
                        .isNull(OutboxEvent::getPublishedAt)
                        .orderByAsc(OutboxEvent::getId)
                        .last("LIMIT " + BATCH_SIZE));
        if (pending.isEmpty()) {
            return;
        }

        // RabbitTemplate.invoke：回调内集中投递，返回后所有 confirm 已到达，逐条判定
        try {
            List<PendingDelivery> deliveries = rabbitTemplate.invoke(operations -> {
                List<PendingDelivery> sent = new ArrayList<>();
                for (OutboxEvent event : pending) {
                    ExportEventMessage message = new ExportEventMessage(1, event.getId(), event.getJobId());
                    String traceId = StringUtils.hasText(event.getTraceId())
                            ? event.getTraceId() : UUID.randomUUID().toString();
                    CorrelationData correlationData = new CorrelationData(String.valueOf(event.getId()));
                    Message amqp = toAmqpMessage(message, traceId);
                    operations.send(RabbitNames.EXPORT_EXCHANGE, RabbitNames.EXPORT_JOB_ROUTING_KEY,
                            amqp, correlationData);
                    sent.add(new PendingDelivery(event, correlationData));
                }
                return sent;
            });

            if (deliveries == null) {
                return;
            }
            for (PendingDelivery delivery : deliveries) {
                CorrelationData cd = delivery.correlationData();
                // RabbitTemplate.invoke 返回前所有 confirm 已到达：future.get() 可立即完成
                boolean acked = isAcked(cd);
                boolean confirmed = acked && cd.getReturned() == null;
                if (confirmed) {
                    markPublished(delivery.event());
                } else {
                    log.warn("outbox 投递未确认/路由失败, 保持未发布(published_at 空)待下轮补投: eventId={}, jobId={}, ack={}",
                            delivery.event().getId(), delivery.event().getJobId(), acked);
                }
            }
        } catch (Exception e) {
            // broker 不可用 / 网络抖动：不改库，行保持 PENDING，等待 MQ 恢复后由后续轮次补投
            log.warn("outbox 投递本轮失败(保持数据库原状, 待下轮重试): {}", e.getMessage());
        }
    }

    private Message toAmqpMessage(ExportEventMessage payload, String traceId) {
        try {
            MessageProperties props = new MessageProperties();
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            props.setHeader(RabbitNames.TRACE_HEADER, traceId);
            return MessageBuilder.withBody(objectMapper.writeValueAsBytes(payload))
                    .andProperties(props).build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("导出投递消息序列化失败", e);
        }
    }

    private void markPublished(OutboxEvent event) {
        int updated = outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEvent>()
                .eq(OutboxEvent::getId, event.getId())
                .isNull(OutboxEvent::getPublishedAt)   // 防并发轮次重复回写
                .set(OutboxEvent::getPublishedAt, LocalDateTime.now()));
        if (updated == 0) {
            return; // 已被并发轮次回写
        }
        // 记录投递日志 trace（便于按 eventId/jobId 追踪）；非请求线程，处理完即清
        try {
            MDC.put(TRACE_KEY, String.valueOf(event.getTraceId()));
            log.info("outbox 事件投递成功: eventId={}, jobId={}", event.getId(), event.getJobId());
        } finally {
            MDC.remove(TRACE_KEY);
        }
    }

    /** 一次待确认投递：outbox 行 + 其 CorrelationData（id = eventId）。 */
    private record PendingDelivery(OutboxEvent event, CorrelationData correlationData) {
    }

    /** broker 是否确认收到：correlated 模式下 CorrelationData.getFuture() 在 confirm 到达后完成。 */
    private boolean isAcked(CorrelationData cd) {
        try {
            CorrelationData.Confirm confirm = cd.getFuture() == null ? null : cd.getFuture().get();
            return confirm != null && confirm.isAck();
        } catch (Exception e) {
            // nack / 连接关闭 / 中断：一律视为未确认，保持 PENDING 待下轮补投
            return false;
        }
    }
}
