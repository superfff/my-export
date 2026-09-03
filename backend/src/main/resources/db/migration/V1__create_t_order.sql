-- Flyway V1: 订单表 DDL（不含种子数据，保持空表）
-- Flyway 会在 Spring Boot 启动时自动执行此脚本

CREATE TABLE IF NOT EXISTS t_order (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    order_no      VARCHAR(64)     NOT NULL COMMENT '订单号',
    customer_name VARCHAR(64)     NOT NULL COMMENT '客户名称',
    phone         VARCHAR(20)     NOT NULL COMMENT '客户手机号',
    status        TINYINT         NOT NULL DEFAULT 1 COMMENT '订单状态：1-未支付 2-已支付 3-已取消',
    amount        DECIMAL(12,2)   NOT NULL DEFAULT 0.00 COMMENT '订单金额',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    remark        VARCHAR(255)    DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_status (status),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单表';
