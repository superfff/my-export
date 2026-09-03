/** 订单状态：1 未支付 / 2 已支付 / 3 已取消 */
export type OrderStatus = 1 | 2 | 3;

/** 后端返回的订单数据结构 */
export interface Order {
  id: number;
  orderNo: string;
  customerName: string;
  phone: string;
  status: OrderStatus;
  amount: number;
  createdAt: string;
  updatedAt?: string;
  remark?: string;
}

/** 排序字段白名单 */
export type SortField = 'amount' | 'createdAt';

/** 排序方向白名单 */
export type SortOrder = 'asc' | 'desc';

/** 订单查询条件（对应 GET /api/order 的 query 参数） */
export interface OrderQuery {
  orderNo?: string;
  customerName?: string;
  phone?: string;
  status?: OrderStatus;
  startTime?: number;
  endTime?: number;
  sortField?: SortField;
  sortOrder?: SortOrder;
  page?: number;
  pageSize?: number;
}

/** 后端分页返回结构 */
export interface PageResult<T> {
  list: T[];
  total: number;
}

/** 后端统一响应包装 */
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  /** 日志链路追踪 ID，由后端 TraceIdFilter 注入 */
  traceId?: string;
}

/** 表格选择模式 */
export enum SelectionMode {
  /** 手动勾选模式：保存勾选的数据 id */
  MANUAL = 'MANUAL',
  /** 全选模式：标识全选 + 保留筛选条件，只记录反选的 id */
  ALL = 'ALL',
}

/** 表格勾选状态 */
export interface SelectionState {
  /** 当前选择模式 */
  mode: SelectionMode;
  /** 手动勾选模式下，选中的数据 id 集合 */
  selectedIds: Set<number>;
  /** 全选模式下，手动反选（排除）的数据 id 集合 */
  excludedIds: Set<number>;
}

/** 导出模式 */
export enum ExportMode {
  /** 导出已选 — 手动勾选 */
  SELECTED = 'SELECTED',
  /** 导出已选 — 全选排除 */
  ALL_EXCLUDE = 'ALL_EXCLUDE',
  /** 导出筛选结果 */
  FILTERED = 'FILTERED',
}

/** 导出任务状态（本期后端恒 PENDING，无推进路径） */
export type ExportJobStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';

/** 后端返回的导出任务（创建成功响应 data） */
export interface ExportJobVO {
  /** 导出任务自增 id */
  id: number;
  /** 导出文件名 */
  filename: string;
  /** 导出场景 */
  exportMode: ExportMode;
  /** 状态 */
  status: ExportJobStatus;
  /** 统计总条数：创建时按本任务范围命中订单总数 */
  expectedTotal: number;
  /** 最大订单 id：创建时命中订单最大 t_order.id（一致性水位） */
  maxOrderId: number;
  /** 创建时间 */
  createdAt: string;
}

/** 导出中心列表行：继承 ExportJobVO；以下 4 个"任务运行指标"为预留，
 *  后端当前不返回（真正导出阶段才填充），本期渲染按缺省（'-'）处理 */
export interface ExportCenterJob extends ExportJobVO {
  actualTotal?: number | null; // 导出实际条数
  progress?: number | null; // 进度百分比 0-100
  finishedAt?: string | null; // 完成时间
  fileSize?: number | null; // 文件大小（字节）
}

/** 导出请求参数 */
export interface ExportParams {
  /** 导出文件名 */
  filename: string;
  /** 勾选的导出字段（dataIndex 值） */
  fields: string[];
  /** 导出模式 */
  mode: ExportMode;
  /** SELECTED 模式：手动勾选的 id */
  selectedIds?: number[];
  /** ALL_EXCLUDE 模式：排除的 id */
  excludedIds?: number[];
  /** ALL_EXCLUDE / FILTERED 模式：当前筛选条件 */
  query?: OrderQuery;
}
