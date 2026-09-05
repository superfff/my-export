package com.example.export.mq;

/**
 * 导出任务的投递消息载体（outbox → 队列 → 消费者）。
 * 只维护关键信息、不携带全量数据库；消费者如需任务全量信息一律按 jobId 回查 export_jobs。
 *
 * @param version 消息结构版本（本期 1）
 * @param eventId outbox_events.id（消费者按其落账 export_job_attempt.event_id）
 * @param jobId   export_jobs.id（消费者按其回查任务/做领取 CAS）
 */
public record ExportEventMessage(int version, Long eventId, Long jobId) {
}
