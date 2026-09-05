-- Flyway V3: outbox 模式支撑表
-- 1) outbox_events：记录"一条导出任务的投递生命周期"（创建链路由 export_jobs + outbox_events 同事务写入）
-- 2) export_job_attempt：记录"消费者每次领取执行的动作"（导出动作表）
-- 3) backfill：把历史 export_jobs 中仍可被消费的 PENDING 行回填为 outbox 事件（它们从未入队）
-- 均为逻辑外键（沿用 V1/V2 不加物理外键的风格），索引保证按发布状态取最早一批的效率

CREATE TABLE IF NOT EXISTS outbox_events (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '事件ID',
    job_id        BIGINT UNSIGNED NOT NULL COMMENT '关联导出任务ID export_jobs.id（1任务=1事件）',
    trace_id      VARCHAR(64)     DEFAULT NULL COMMENT '创建请求 traceId（历史 backfill 行为 NULL，投递时兜底生成）',
    status        VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '发布状态:PENDING/PUBLISHED',
    published_at  DATETIME        DEFAULT NULL COMMENT '投递成功(broker confirm 且无路由 return)时间;与 status=PUBLISHED 同翻',
    attempt_count INT             NOT NULL DEFAULT 0 COMMENT '导出执行尝试次数(=消费者成功领取次数,恒等于该任务 export_job_attempt 行数)',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_job_id (job_id),
    KEY idx_outbox_pending (status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'outbox 事件表:导出任务投递生命周期';

CREATE TABLE IF NOT EXISTS export_job_attempt (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '尝试记录ID',
    event_id      BIGINT UNSIGNED NOT NULL COMMENT '关联 outbox_events.id(消费者按消息 eventId 落账)',
    job_id        BIGINT UNSIGNED NOT NULL COMMENT '关联导出任务ID export_jobs.id',
    attempt_no    INT             NOT NULL COMMENT '该任务第几次执行(本期配合CAS单次领取恒为1)',
    status        VARCHAR(16)     NOT NULL COMMENT '本次执行状态:RUNNING/SUCCESS/FAILED(本期恒RUNNING;后续阶段执行结束回填终态)',
    started_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始执行时间',
    finished_at   DATETIME        DEFAULT NULL COMMENT '结束时间(后续真实导出阶段回填)',
    error_message VARCHAR(1000)   DEFAULT NULL COMMENT '失败原因(后续真实导出阶段回填)',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_attempt (job_id, attempt_no),
    KEY idx_attempt_event (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '导出任务执行尝试表:记录每次领取执行的动作信息';

-- backfill 历史数据：export_jobs 中 PENDING(从未入队、仍需被消费) 的行同步为 outbox 事件
INSERT INTO outbox_events (job_id, trace_id, status, published_at, attempt_count)
SELECT id, NULL, 'PENDING', NULL, 0
FROM export_jobs
WHERE status = 'PENDING';
