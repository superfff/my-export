package com.example.export.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * outbox 事件实体，对应数据库表 outbox_events。
 * 记录"一条导出任务的投递生命周期"：创建链路由 export_jobs + 本表同一事务写入；
 * dispatcher 仅扫描"未发布行(published_at IS NULL)"投递、收到 broker confirm 且无路由 return
 * 后回写 published_at。任务级状态不在此表重复存储（见 export_jobs.status）。
 */
@TableName("outbox_events")
public class OutboxEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联导出任务 id（export_jobs.id），1 任务 = 1 事件 */
    private Long jobId;

    /** 创建请求 traceId（历史 backfill 行为 NULL，投递时兜底生成） */
    private String traceId;

    /** 投递成功（broker confirm 且无 return）时间；NULL=未发布（dispatcher 扫描依据） */
    private LocalDateTime publishedAt;

    /** 导出执行尝试次数（= 消费者成功领取次数，恒等于该任务 export_job_attempt 行数） */
    private Integer attemptCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
