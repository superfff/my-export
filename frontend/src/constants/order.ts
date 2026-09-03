import type { OrderStatus } from '../types/order';

interface StatusMeta {
  text: string;
  color: string;
}

export const ORDER_STATUS: Record<OrderStatus, StatusMeta> = {
  1: { text: '未支付', color: 'warning' },
  2: { text: '已支付', color: 'success' },
  3: { text: '已取消', color: 'default' },
};

export const ORDER_STATUS_OPTIONS = (Object.entries(ORDER_STATUS) as [string, StatusMeta][]).map(
  ([value, { text }]) => ({ value: Number(value) as OrderStatus, label: text }),
);

/** 选择上限：手动模式最多勾选 100 条，全选模式最多反选 100 条 */
export const MAX_SELECTION = 100;
