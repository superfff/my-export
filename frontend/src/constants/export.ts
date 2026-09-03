import type { ExportJobStatus, ExportMode } from '../types/order';

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

/** 导出模式 → 展示文案（对应订单页"导出已选/导出筛选结果/全选反选"口径） */
export const EXPORT_MODE_TEXT: Record<ExportMode, string> = {
  SELECTED: '导出已选',
  ALL_EXCLUDE: '全选反选导出',
  FILTERED: '导出筛选结果',
};

/** 任务状态 → 展示元信息 */
export const EXPORT_JOB_STATUS: Record<ExportJobStatus, { text: string; color: string }> = {
  PENDING: { text: '待导出', color: 'default' },
  RUNNING: { text: '导出中', color: 'processing' },
  SUCCESS: { text: '成功', color: 'success' },
  FAILED: { text: '失败', color: 'error' },
};

/** 状态 tab 选项："全部" + 四状态 */
export type ExportStatusTab = ExportJobStatus | 'ALL';
export const EXPORT_JOB_STATUS_TABS: { key: ExportStatusTab; label: string }[] = [
  { key: 'ALL', label: '全部' },
  { key: 'PENDING', label: '待导出' },
  { key: 'RUNNING', label: '导出中' },
  { key: 'SUCCESS', label: '成功' },
  { key: 'FAILED', label: '失败' },
];
