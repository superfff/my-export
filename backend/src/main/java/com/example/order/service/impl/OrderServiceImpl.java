package com.example.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.order.common.PageResult;
import com.example.order.dto.OrderQueryDTO;
import com.example.order.dto.OrderVO;
import com.example.order.entity.Order;
import com.example.order.mapper.OrderMapper;
import com.example.order.service.OrderService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 订单业务层实现。
 */
@Service
public class OrderServiceImpl implements OrderService {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final OrderMapper orderMapper;

    public OrderServiceImpl(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    public PageResult<OrderVO> page(OrderQueryDTO query) {
        long pageNum = query.page() == null || query.page() < 1 ? 1 : query.page();
        long pageSize = query.pageSize() == null || query.pageSize() < 1 ? 20 : query.pageSize();

        // 拼装查询条件：只有传了对应参数才追加，未传的字段不参与过滤
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(query.orderNo()), Order::getOrderNo, query.orderNo());
        wrapper.like(StringUtils.hasText(query.customerName()), Order::getCustomerName, query.customerName());
        wrapper.like(StringUtils.hasText(query.phone()), Order::getPhone, query.phone());
        wrapper.eq(query.status() != null, Order::getStatus, query.status());
        if (query.startTime() != null) {
            wrapper.ge(Order::getCreatedAt, toLocalDateTime(query.startTime()));
        }
        if (query.endTime() != null) {
            wrapper.le(Order::getCreatedAt, toLocalDateTime(query.endTime()));
        }
        wrapper.orderByDesc(Order::getCreatedAt);

        Page<Order> page = orderMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);

        List<OrderVO> list = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(list, page.getTotal(), pageNum, pageSize);
    }

    private LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE);
    }

    private OrderVO toVO(Order order) {
        return new OrderVO(
                order.getId(),
                order.getOrderNo(),
                order.getCustomerName(),
                order.getPhone(),
                order.getStatus(),
                order.getAmount(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getRemark()
        );
    }
}
