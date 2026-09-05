package com.example.export.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 导出任务执行尝试实体，对应数据库表 export_job_attempt。
 * 记录"消费者每次领取执行的动作"：一次执行开始时 status=RUNNING；
 * 后续真实导出阶段在该次执行结束回填 SUCCESS/FAILED（连同 finished_at / error_message）。
 * 前瞻：将来任务可重试成新一次执行时，一行代表一次执行；export_jobs.status 取最近一次执行结果。
 */
@TableName("export_job_attempt")
public class ExportJobAttempt {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 outbox_events.id（消费者按消息 eventId 落账） */
    private Long eventId;

    /** 关联 export_jobs.id */
    private Long jobId;

    /** 该任务第几次执行（本期配合 CAS 单次领取恒为 1） */
    private Integer attemptNo;

    /** 本次执行状态：RUNNING/SUCCESS/FAILED */
    private String status;

    /** 开始执行时间 */
    private LocalDateTime startedAt;

    /** 结束时间（后续真实导出阶段回填） */
    private LocalDateTime finishedAt;

    /** 失败原因（后续真实导出阶段回填） */
    private String errorMessage;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public Integer getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(Integer attemptNo) {
        this.attemptNo = attemptNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
