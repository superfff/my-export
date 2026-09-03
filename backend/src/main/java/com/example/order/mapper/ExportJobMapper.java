package com.example.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.order.entity.ExportJob;
import org.apache.ibatis.annotations.Mapper;

/**
 * 异步导出任务数据访问层。继承 BaseMapper 自带单表 CRUD。
 */
@Mapper
public interface ExportJobMapper extends BaseMapper<ExportJob> {
}
