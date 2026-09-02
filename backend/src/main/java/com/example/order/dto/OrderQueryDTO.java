package com.example.order.dto;

/**
 * 订单查询请求参数。前端 GET /api/order 的查询条件会绑定到这个 DTO。
 *
 * @param orderNo      订单号（模糊匹配）
 * @param customerName 客户名称（模糊匹配）
 * @param phone        客户手机号（模糊匹配）
 * @param status       订单状态（1/2/3，精确匹配）
 * @param startTime    创建时间起点（毫秒时间戳）
 * @param endTime      创建时间终点（毫秒时间戳）
 * @param page         页码，从 1 开始
 * @param pageSize     每页条数
 */
public record OrderQueryDTO(
        String orderNo,
        String customerName,
        String phone,
        Integer status,
        Long startTime,
        Long endTime,
        Long page,
        Long pageSize
) {
}
