package com.example.export.dto;

import com.example.export.enums.ExportMode;

import java.util.List;

/**
 * 创建导出任务请求体。query 仅承载筛选谓词（毫秒时间戳），
 * 与订单列表查询一致；多余字段由 Jackson 默认忽略。
 *
 * @param filename    导出文件名（trim 后非空，≤255）
 * @param fields      勾选导出列，值 ∈ 白名单（保序）
 * @param mode        导出场景
 * @param query       筛选条件（FILTERED / ALL_EXCLUDE 生效）
 * @param selectedIds 已选订单 id（SELECTED 必填，≤100）
 * @param excludedIds 排除订单 id（ALL_EXCLUDE 可选，≤100）
 */
public record ExportCreateRequest(
        String filename,
        List<String> fields,
        ExportMode mode,
        Query query,
        List<Long> selectedIds,
        List<Long> excludedIds
) {

    /** 订单列表筛选谓词（字段含义与 GET /api/order 查询参数一致，但独立声明、不复用列表接口） */
    public record Query(
            String orderNo,
            String customerName,
            String phone,
            Integer status,
            Long startTime,
            Long endTime
    ) {
    }
}
