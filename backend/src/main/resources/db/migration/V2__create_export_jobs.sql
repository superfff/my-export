-- Flyway V2: 异步导出任务表 DDL
-- 仅建表，不含种子数据

CREATE TABLE IF NOT EXISTS export_jobs (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    idempotency_key VARCHAR(64)     NOT NULL COMMENT '幂等键(UUIDv4)，意图唯一',
    request_hash    CHAR(64)        NOT NULL COMMENT '导出内容指纹:sha256(规范化列+范围)，不含文件名/幂等键/jobId',
    export_mode     VARCHAR(16)     NOT NULL COMMENT '导出场景:SELECTED/ALL_EXCLUDE/FILTERED',
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '状态机:PENDING/RUNNING/SUCCESS/FAILED',
    filename        VARCHAR(255)    NOT NULL COMMENT '导出文件名',
    export_columns  VARCHAR(1000)   NOT NULL COMMENT '勾选导出列(JSON数组，保留用户选择顺序)',
    scope_snapshot  TEXT            NOT NULL COMMENT '导出范围快照(规范化JSON:mode+query+ids)',
    expected_total  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '统计总条数:创建时按本任务筛选/范围对t_order聚合的命中订单总数(供导出中心展示/后续导出进度分母)',
    max_order_id    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '最大订单id:创建时命中订单的最大t_order.id(即"当前查询条件下最大的jobId"，一致性水位，后续导出按 id<=该值 防漂移)',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_key (idempotency_key),
    KEY idx_request_hash (request_hash),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '异步导出任务表';
