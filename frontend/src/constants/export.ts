/**
 * 导出相关常量。
 * 409 两条文案与后端 ExportJobServiceImpl 逐字一致（前端据此区分两种 409）。
 */
export const EXPORT_409_MESSAGES = {
  /** request_hash 相同：已有相同导出任务，前端当作本次提交成功闭环 */
  duplicate: '已有相同导出任务',
  /** request_hash 不同：幂等值冲突，视为异常 */
  conflict: '幂等值冲突',
} as const;
