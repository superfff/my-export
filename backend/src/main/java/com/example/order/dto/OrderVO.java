package com.example.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单查询响应对象（返回给前端的数据结构）。
 */
public record OrderVO(
        Long id,
        String orderNo,
        String customerName,
        String phone,
        Integer status,
        BigDecimal amount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String remark
) {
}
