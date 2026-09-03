package com.example.export.dto;

/**
 * 导出任务列表查询参数（GET /api/export-job）。
 *
 * @param status   任务状态筛选，取值 PENDING / RUNNING / SUCCESS / FAILED；不传 = 全部
 * @param page     页码，从 1 起，缺省 / 非法(<1) → 1
 * @param pageSize 每页条数，缺省 / 非法(<1) → 20
 */
public record ExportJobQueryDTO(String status, Long page, Long pageSize) {
}
