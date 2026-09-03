package com.example.order.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.example.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * 订单数据访问层。继承 BaseMapper 后自带单表 CRUD 与分页能力，
 * 复杂查询通过 Service 层的 LambdaQueryWrapper 拼接，无需手写 SQL。
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 导出任务创建时的只读快照聚合：复用 Service 层拼装的同一套筛选/范围谓词，
     * 单条 SQL 一次取 count 与 max(id)，保证同一时刻一致性（作为导出前统计条数与一致性水位）。
     *
     * @param wrapper 谓词（含筛选与按 mode 的 id 范围），无条件时统计全表
     * @return map：total=命中总数，maxId=命中订单最大 id(空集时 COALESCE 兜底 0)
     */
    @Select("SELECT COUNT(*) AS total, COALESCE(MAX(id), 0) AS maxId FROM t_order ${ew.customSqlSegment}")
    Map<String, Object> selectExportScopeStats(@Param(Constants.WRAPPER) Wrapper<Order> wrapper);
}

