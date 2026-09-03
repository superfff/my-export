package com.example.export.enums;

/**
 * 导出模式：对应前端三种交互场景。
 */
public enum ExportMode {

    /** 导出已选 — 手动勾选 */
    SELECTED,

    /** 导出已选 — 全选反选（query 筛选 + excludedIds 排除） */
    ALL_EXCLUDE,

    /** 导出筛选结果（query 筛选全量命中数据） */
    FILTERED
}
