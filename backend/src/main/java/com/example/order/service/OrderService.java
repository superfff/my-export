package com.example.order.service;

import com.example.common.PageResult;
import com.example.order.dto.OrderQueryDTO;
import com.example.order.dto.OrderVO;

/**
 * 订单业务层接口。
 */
public interface OrderService {

    /**
     * 按条件分页查询订单。
     */
    PageResult<OrderVO> page(OrderQueryDTO query);
}
