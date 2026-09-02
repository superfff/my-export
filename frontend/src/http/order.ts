import { get } from './request';
import type { Order, OrderQuery, PageResult } from '../types/order';

/**
 * 订单分页查询。
 * 调用后端 GET /api/order，把查询条件作为 query 参数传递。
 */
export function fetchOrders(params: OrderQuery = {}): Promise<PageResult<Order>> {
  return get<PageResult<Order>>('/api/order', params);
}
