package com.example.export.enums;

/**
 * 导出任务状态机。
 * 流转：PENDING→RUNNING→SUCCESS/FAILED；FAILED→(重试)→PENDING；SUCCESS→(文件过期回收)→EXPIRED。
 */
public enum ExportJobStatus {

    /** 等待导出 */
    PENDING,

    /** 正在导出 */
    RUNNING,

    /** 导出成功 */
    SUCCESS,

    /** 导出失败（仅该状态可重试） */
    FAILED,

    /** 文件已过期清理（仅 SUCCESS→EXPIRED，由过期回收扫描置入；不可再下载/重试） */
    EXPIRED
}
