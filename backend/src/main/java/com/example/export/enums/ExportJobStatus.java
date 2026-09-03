package com.example.export.enums;

/**
 * 导出任务状态机。本期入库后停留在 PENDING，无任何代码路径推进状态。
 */
public enum ExportJobStatus {

    /** 等待导出 */
    PENDING,

    /** 正在导出 */
    RUNNING,

    /** 导出成功 */
    SUCCESS,

    /** 导出失败 */
    FAILED
}
