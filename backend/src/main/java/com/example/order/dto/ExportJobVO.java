package com.example.order.dto;

import com.example.order.enums.ExportJobStatus;
import com.example.order.enums.ExportMode;

import java.time.LocalDateTime;

/**
 * 导出任务视图对象（创建成功返回）。
 *
 * @param id           导出任务自增 id
 * @param filename     导出文件名
 * @param exportMode   导出场景
 * @param status       状态（本期恒 PENDING）
 * @param expectedTotal 统计总条数（创建时命中订单总数）
 * @param maxOrderId   最大订单 id（创建时命中订单最大 t_order.id，一致性水位）
 * @param createdAt    创建时间
 */
public record ExportJobVO(
        Long id,
        String filename,
        ExportMode exportMode,
        ExportJobStatus status,
        Long expectedTotal,
        Long maxOrderId,
        LocalDateTime createdAt
) {
}
