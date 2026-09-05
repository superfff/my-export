package com.example.export.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.export.entity.OutboxEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * outbox 事件数据访问层。继承 BaseMapper 自带单表 CRUD；
 * dispatcher 批量扫描与发布状态回写在此基础上用 LambdaQuery/UpdateWrapper 实现。
 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {
}
