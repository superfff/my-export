package com.example.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单数据访问层。继承 BaseMapper 后自带单表 CRUD 与分页能力，
 * 复杂查询通过 Service 层的 LambdaQueryWrapper 拼接，无需手写 SQL。
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
