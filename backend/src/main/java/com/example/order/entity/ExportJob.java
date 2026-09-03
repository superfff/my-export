package com.example.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 异步导出任务实体，对应数据库表 export_jobs。
 */
@TableName("export_jobs")
public class ExportJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等键(UUIDv4)，意图唯一 */
    private String idempotencyKey;

    /** 导出内容指纹 sha256(规范化列+范围) */
    private String requestHash;

    /** 导出模式：SELECTED/ALL_EXCLUDE/FILTERED */
    private String exportMode;

    /** 状态：PENDING/RUNNING/SUCCESS/FAILED */
    private String status;

    /** 导出文件名 */
    private String filename;

    /** 勾选导出列(JSON 数组，保留用户选择顺序) */
    private String exportColumns;

    /** 导出范围快照(规范化 JSON：mode+query+ids) */
    private String scopeSnapshot;

    /** 统计总条数：创建时按本任务筛选/范围聚合的命中订单总数 */
    private Long expectedTotal;

    /** 最大订单 id：创建时命中订单的最大 t_order.id(一致性水位) */
    private Long maxOrderId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public String getExportMode() {
        return exportMode;
    }

    public void setExportMode(String exportMode) {
        this.exportMode = exportMode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getExportColumns() {
        return exportColumns;
    }

    public void setExportColumns(String exportColumns) {
        this.exportColumns = exportColumns;
    }

    public String getScopeSnapshot() {
        return scopeSnapshot;
    }

    public void setScopeSnapshot(String scopeSnapshot) {
        this.scopeSnapshot = scopeSnapshot;
    }

    public Long getExpectedTotal() {
        return expectedTotal;
    }

    public void setExpectedTotal(Long expectedTotal) {
        this.expectedTotal = expectedTotal;
    }

    public Long getMaxOrderId() {
        return maxOrderId;
    }

    public void setMaxOrderId(Long maxOrderId) {
        this.maxOrderId = maxOrderId;
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
