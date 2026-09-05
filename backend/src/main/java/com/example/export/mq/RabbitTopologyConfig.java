package com.example.export.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明（业务侧）：持久化 direct 业务交换机 + 业务队列(挂死信) + 死信交换机 + 死信队列。
 * 全部 durable；声明式 Bean 在应用启动时自动建到 broker，不在管理台手点。
 *
 * <pre>
 * export.exchange(direct)
 *   └─ routing key export.job ────────────► export.job.queue(durable; DLX=export.dlx, DL-RK=export.job.dlq)
 * export.dlx(direct)
 *   └─ routing key export.job.dlq ────────► export.job.dlq(durable)
 * </pre>
 */
@Configuration
public class RabbitTopologyConfig {

    @Bean
    public DirectExchange exportExchange() {
        return new DirectExchange(RabbitNames.EXPORT_EXCHANGE, true, false);
    }

    @Bean
    public Queue exportJobQueue() {
        return QueueBuilder.durable(RabbitNames.EXPORT_JOB_QUEUE)
                .deadLetterExchange(RabbitNames.EXPORT_DLX)
                .deadLetterRoutingKey(RabbitNames.EXPORT_JOB_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public DirectExchange exportDlx() {
        return new DirectExchange(RabbitNames.EXPORT_DLX, true, false);
    }

    @Bean
    public Queue exportJobDlq() {
        return QueueBuilder.durable(RabbitNames.EXPORT_JOB_DLQ).build();
    }

    @Bean
    public Binding exportJobBinding(DirectExchange exportExchange, Queue exportJobQueue) {
        return BindingBuilder.bind(exportJobQueue).to(exportExchange)
                .with(RabbitNames.EXPORT_JOB_ROUTING_KEY);
    }

    @Bean
    public Binding exportDlqBinding(DirectExchange exportDlx, Queue exportJobDlq) {
        return BindingBuilder.bind(exportJobDlq).to(exportDlx)
                .with(RabbitNames.EXPORT_JOB_DLQ_ROUTING_KEY);
    }
}
