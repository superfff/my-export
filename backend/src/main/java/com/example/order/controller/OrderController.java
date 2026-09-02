package com.example.order.controller;

import com.example.order.common.ApiResponse;
import com.example.order.common.PageResult;
import com.example.order.dto.OrderQueryDTO;
import com.example.order.dto.OrderVO;
import com.example.order.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口层。统一前缀 /api。
 */
@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 订单分页查询。GET /api/order?orderNo=...&status=...&page=1&pageSize=20
     */
    @GetMapping("/order")
    public ApiResponse<PageResult<OrderVO>> queryOrders(OrderQueryDTO query) {
        return ApiResponse.ok(orderService.page(query));
    }
}
