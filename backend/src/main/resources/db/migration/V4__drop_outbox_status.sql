-- Flyway V4: outbox_events 移除 status 列。
-- 发布与否以 published_at IS NULL 为准（status 与 published_at 冗余且无实际逻辑，仅让程序复杂化）。
-- 先删以其为首列的联合索引（MySQL 单条 ALTER 内先 DROP INDEX 再 DROP COLUMN），避免残留退化索引。
ALTER TABLE outbox_events
    DROP INDEX idx_outbox_pending,
    DROP COLUMN status;
