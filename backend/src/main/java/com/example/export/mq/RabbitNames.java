package com.example.export.mq;

/**
 * RabbitMQ 交换机 / 队列 / routing key / 链路头常量。
 * 拓扑声明(RabbitTopologyConfig)与投递、消费侧统一引用，避免字符串散落。
 */
public final class RabbitNames {

    private RabbitNames() {
    }

    /** 业务交换机：direct、durable */
    public static final String EXPORT_EXCHANGE = "export.exchange";

    /** 业务队列 routing key（消息经业务交换机按此键路由进主队列） */
    public static final String EXPORT_JOB_ROUTING_KEY = "export.job";

    /** 业务主队列：durable，声明死信交换机/死信路由（超过容器重试上限后 reject 进死信） */
    public static final String EXPORT_JOB_QUEUE = "export.job.queue";

    /** 死信交换机：direct、durable */
    public static final String EXPORT_DLX = "export.dlx";

    /** 死信路由与死信队列（同名同键，消息最终落在 export.job.dlq） */
    public static final String EXPORT_JOB_DLQ_ROUTING_KEY = "export.job.dlq";
    public static final String EXPORT_JOB_DLQ = "export.job.dlq";

    /** 消息头：投递/消费链路共享的 traceId（与 HTTP 头 x-trace-id 同名同义） */
    public static final String TRACE_HEADER = "x-trace-id";
}
