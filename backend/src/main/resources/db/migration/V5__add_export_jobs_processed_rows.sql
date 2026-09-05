-- Flyway V5: export_jobs 增加导出进度字段（已成功写入文件的行数）。
ALTER TABLE export_jobs
    ADD COLUMN processed_rows BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '已处理行数:已成功写入excel的行数,导出进度;RUNNING期间分批递增,终态=实际导出数'
        AFTER max_order_id;
