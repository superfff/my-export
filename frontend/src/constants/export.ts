import type { ExportJobStatus, ExportMode } from '../types/order';

/**
 * 导出相关常量。
 * 409 两条文案与后端 ExportJobServiceImpl 逐字一致：既是判别串，也是直接展示给用户的后端原文
 *（前端不拼接自定义文案，后端 message 即最终文本）。
 */
export const EXPORT_409_MESSAGES = {
  /** request_hash 相同：已有相同导出任务，前端当作本次提交成功闭环（关弹窗） */
  duplicate: '已有相同导出任务',
  /** request_hash 不同：幂等值冲突，视为异常生命周期（保留弹窗） */
  conflict: '幂等值冲突',
} as const;

/** 409 两种语义判别：同 request_hash → duplicate；异 request_hash → conflict */
export type Export409Kind = 'duplicate' | 'conflict';

/**
 * 用后端 message 原文判别 409 语义。
 * duplicate 文案在后端是逐字常量，直接比对；其余一律视为 conflict（含后端未来新增文案，
 * 保持"未知冲突不误导为已创建"的安全语义）。
 */
export function classifyExport409(message: string): Export409Kind {
  return message === EXPORT_409_MESSAGES.duplicate ? 'duplicate' : 'conflict';
}

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

/** id 数组升序（副本，不改原 Set/数组），供"导出已选"两类场景（手动勾选 / 全选反选）的请求参数统一排序 */
export function ascSortedIds(ids: Iterable<number>): number[] {
  return [...ids].sort((a, b) => a - b);
}

/**
 * 进度百分比：SUCCESS → 100；否则封顶 99（"完成"以状态 tag 表达，避免 RUNNING 误显示 100）。
 * expectedTotal <= 0 → null（无法计算，显示 '-'）。
 */
export function exportProgressPercent(p: {
  status: ExportJobStatus;
  processedRows: number;
  expectedTotal: number;
}): number | null {
  if (p.expectedTotal <= 0) return null;
  const ratio = Math.round((p.processedRows / p.expectedTotal) * 100);
  return p.status === 'SUCCESS' ? 100 : Math.min(99, Math.max(0, ratio));
}
