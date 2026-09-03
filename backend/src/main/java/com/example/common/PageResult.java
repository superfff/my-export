package com.example.common;

import java.util.List;

/**
 * 分页结果，返回给前端。
 *
 * @param list     当前页数据
 * @param total    总条数
 * @param page     当前页码
 * @param pageSize 每页条数
 */
public record PageResult<T>(List<T> list, long total, long page, long pageSize) {
}
