package com.example.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate.ReturnsCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 基础设施配置：提供与业务无关的 RabbitTemplate，开启 mandatory（不可路由即 return）。
 * correlated 发布确认与 publisher-returns 由连接工厂承载（application.yml 的
 * spring.rabbitmq.publisher-confirm-type / publisher-returns 已开启），dispatcher 通过
 * CorrelationData.getFuture()/getReturned() 判定"confirm 且无 return 才算投递成功"。
 * 队列/交换机拓扑声明在业务模块 com.example.export.mq.RabbitTopologyConfig。
 */
@Configuration
public class RabbitConfig {

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        // 兜底日志：正常路径由 dispatcher 用 CorrelationData 判定，此处仅当拓扑异常(不可路由)时留痕
        template.setReturnsCallback((ReturnsCallback) returned -> org.slf4j.LoggerFactory
                .getLogger(RabbitConfig.class)
                .warn("RabbitMQ 消息路由失败(returned): exchange={}, routingKey={}, replyText={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        return template;
    }
}
