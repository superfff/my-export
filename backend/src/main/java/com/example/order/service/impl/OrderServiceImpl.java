package com.example.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.common.ApiResponse;
import com.example.common.PageResult;
import com.example.common.SortParamValidator;
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
        // 排序参数白名单校验
        String sortError = SortParamValidator.validate(query.sortField(), query.sortOrder());
        if (sortError != null) {
            throw new IllegalArgumentException(sortError);
        }

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

        // 排序：传了 sortField/sortOrder 时按指定字段排序，否则默认 created_at DESC
        // 稳定分页：始终追加 id ASC 作为第二排序键，避免分页漂移
        if (query.sortField() != null && query.sortOrder() != null) {
            boolean isAsc = "asc".equalsIgnoreCase(query.sortOrder());
            applySort(wrapper, query.sortField(), isAsc);
        } else {
            wrapper.orderByDesc(Order::getCreatedAt);
        }
        // 第二排序键：id 升序，确保排序值相同时分页结果稳定
        wrapper.orderByAsc(Order::getId);

        Page<Order> page = orderMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);

        List<OrderVO> list = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(list, page.getTotal(), pageNum, pageSize);
    }

    /**
     * 根据字段名和方向应用排序。
     * 使用 LambdaQueryWrapper 的类型安全排序，避免字符串拼接。
     */
    private void applySort(LambdaQueryWrapper<Order> wrapper, String sortField, boolean isAsc) {
        if ("amount".equals(sortField)) {
            if (isAsc) wrapper.orderByAsc(Order::getAmount);
            else wrapper.orderByDesc(Order::getAmount);
        } else if ("createdAt".equals(sortField)) {
            if (isAsc) wrapper.orderByAsc(Order::getCreatedAt);
            else wrapper.orderByDesc(Order::getCreatedAt);
        }
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
