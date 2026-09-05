package com.example.export.dto;

import com.example.export.enums.ExportJobStatus;
import com.example.export.enums.ExportMode;

import java.time.LocalDateTime;

/**
 * 导出任务视图对象（创建与列表查询共用，字段口径保持一致）。
 *
 * @param id            导出任务自增 id
 * @param filename      导出文件名
 * @param exportMode    导出场景
 * @param status        任务状态（PENDING / RUNNING / SUCCESS / FAILED）
 * @param expectedTotal 统计总条数（创建时命中订单总数，进度分母）
 * @param processedRows 已成功写入 excel 的行数（导出进度；RUNNING 期间分批递增，终态=实际导出条数）
 * @param maxOrderId    最大订单 id（创建时命中订单最大 t_order.id，一致性水位）
 * @param createdAt     创建时间
 */
public record ExportJobVO(
        Long id,
        String filename,
        ExportMode exportMode,
        ExportJobStatus status,
        Long expectedTotal,
        Long processedRows,
        Long maxOrderId,
        LocalDateTime createdAt
) {
}
