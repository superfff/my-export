package com.example.export.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.export.entity.ExportJobAttempt;
import org.apache.ibatis.annotations.Mapper;

/**
 * 导出任务执行尝试数据访问层。继承 BaseMapper 自带单表 CRUD。
 */
@Mapper
public interface ExportJobAttemptMapper extends BaseMapper<ExportJobAttempt> {
}
