import { useCallback, useEffect, useRef, useState } from 'react';
import { Tag, Button, Space, message } from 'antd';
import type { TableColumnsType } from 'antd';
import PageTable from '../../components/PageTable';
import { createInitialSelectionState } from '../../components/PageTable';
import ExportModal from '../../components/ExportModal';
import OrderQueryForm from './OrderQueryForm';
import type { OrderFormValues } from './OrderQueryForm';
import { fetchOrders } from '../../http/order';
import { submitExportJob } from '../../http/export';
import type { ExportJobRequest } from '../../http/export';
import { ApiError } from '../../http/request';
import { ascSortedIds, classifyExport409 } from '../../constants/export';
import { ORDER_STATUS, MAX_SELECTION } from '../../constants/order';
import type { Order, OrderQuery, OrderStatus, SelectionState } from '../../types/order';
import { ExportMode, SelectionMode } from '../../types/order';
import { formatDateTime } from '../../utils/format';
import styles from './index.module.css';

const DEFAULT_PAGE_SIZE = 20;

/** 排序默认值：创建时间 降序（与表单 initialValues 一致；首次加载与重置都显式携带 sort 参数） */
const DEFAULT_SORT: Pick<OrderQuery, 'sortField' | 'sortOrder'> = {
  sortField: 'createdAt',
  sortOrder: 'desc',
};

const columns: TableColumnsType<Order> = [
  { title: '订单号', dataIndex: 'orderNo', width: 190, ellipsis: true },
  { title: '客户名称', dataIndex: 'customerName', width: 120 },
  { title: '客户手机号', dataIndex: 'phone', width: 140 },
  {
    title: '订单金额',
    dataIndex: 'amount',
    width: 120,
    render: (amount: number) => `¥${amount.toFixed(2)}`,
  },
  {
    title: '订单状态',
    dataIndex: 'status',
    width: 110,
    render: (status: OrderStatus) => (
      <Tag color={ORDER_STATUS[status].color}>{ORDER_STATUS[status].text}</Tag>
    ),
  },
  {
    title: '订单创建时间',
    dataIndex: 'createdAt',
    width: 180,
    render: (ts: string) => formatDateTime(ts),
  },
];

/** 从 columns 提取表头字段选项，供导出弹窗使用 */
const exportColumnOptions = columns
  .filter((col) => 'dataIndex' in col && col.dataIndex != null)
  .map((col) => ({
    label: typeof col.title === 'string' ? col.title : '',
    value: String((col as Record<string, unknown>).dataIndex),
  }));

/** 导出入口类型 */
type ExportEntry = 'selected' | 'filtered';

/** 进行中的导出意图：同一内容重试复用同一幂等 Key，内容变化重新生成 */
interface PendingExport {
  key: string;
  /** 归一化导出内容的签名（文件名除外，与后端 request_hash 口径一致） */
  sig: string;
}

/** 从筛选状态抽取后端导出的筛选谓词（只保留六键，去掉分页/排序） */
function filterQuery(query: OrderQuery): NonNullable<ExportJobRequest['query']> {
  const out: NonNullable<ExportJobRequest['query']> = {};
  if (query.orderNo) out.orderNo = query.orderNo.trim();
  if (query.customerName) out.customerName = query.customerName.trim();
  if (query.phone) out.phone = query.phone.trim();
  if (query.status !== undefined && query.status !== null) out.status = query.status;
  if (query.startTime !== undefined && query.startTime !== null) out.startTime = query.startTime;
  if (query.endTime !== undefined && query.endTime !== null) out.endTime = query.endTime;
  return out;
}

/** 生成幂等 Key：优先 crypto.randomUUID，非安全上下文降级 */
function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `idem-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/** 归一化导出内容的签名：固定键序、id/字段排序，确保"相同内容 → 相同签名" */
function buildSignature(body: ExportJobRequest): string {
  const q = body.query ?? {};
  const queryCanon: Record<string, unknown> = {};
  (['orderNo', 'customerName', 'phone', 'status', 'startTime', 'endTime'] as const).forEach((key) => {
    const value = q[key];
    if (value !== undefined && value !== null && value !== '') queryCanon[key] = value;
  });
  return JSON.stringify({
    mode: body.mode,
    fields: [...body.fields].sort(),
    query: queryCanon,
    selectedIds: [...(body.selectedIds ?? [])].sort((a, b) => a - b),
    excludedIds: [...(body.excludedIds ?? [])].sort((a, b) => a - b),
  });
}

export default function OrderList() {
  const [list, setList] = useState<Order[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<OrderQuery>({ ...DEFAULT_SORT });
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  // 勾选状态（跨页保留）
  const [selectionState, setSelectionState] = useState<SelectionState>(
    createInitialSelectionState,
  );

  // 导出弹窗状态
  const [exportOpen, setExportOpen] = useState(false);
  const [exportTitle, setExportTitle] = useState('');
  const [exportEntry, setExportEntry] = useState<ExportEntry>('selected');

  // 幂等 Key 生命周期：只持有"提交中 / 失败待重试"的导出意图
  const pendingRef = useRef<PendingExport | null>(null);
  const clearPending = () => {
    pendingRef.current = null;
  };

  // 拉取订单数据，依赖 query/page/pageSize 变化时重新请求
  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const result = await fetchOrders({ ...query, page, pageSize });
      setList(result.list);
      setTotal(result.total);
    } catch (err) {
      console.error('查询订单失败：', err);
    } finally {
      setLoading(false);
    }
  }, [query, page, pageSize]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // 把表单的时间范围转换为毫秒时间戳，作为 startTime/endTime 传给后端
  // 空值保护：过滤掉 undefined/null/空字符串，防止无效值传给后端
  const handleSearch = (values: OrderFormValues) => {
    const { createdRange, ...rest } = values;
    const nextQuery: OrderQuery = {};
    // 只保留有效值（非 undefined、非 null、非空字符串）
    for (const [key, value] of Object.entries(rest)) {
      if (value !== undefined && value !== null && value !== '') {
        (nextQuery as Record<string, unknown>)[key] = value;
      }
    }
    if (createdRange && createdRange.length === 2 && createdRange[0] && createdRange[1]) {
      nextQuery.startTime = createdRange[0].startOf('day').valueOf();
      nextQuery.endTime = createdRange[1].endOf('day').valueOf();
    }
    setQuery(nextQuery);
    setPage(1);
    // 查询条件变更时重置勾选状态
    setSelectionState(createInitialSelectionState());
    // 筛选变化会使导出内容改变，作废进行中的导出意图
    clearPending();
  };

  const handleReset = () => {
    setQuery({ ...DEFAULT_SORT });
    setPage(1);
    // 重置时清空勾选
    setSelectionState(createInitialSelectionState());
    clearPending();
  };

  const handlePageChange = (p: number, ps: number) => {
    setPage(p);
    setPageSize(ps);
  };

  const handleSelectionChange = (nextState: SelectionState) => {
    setSelectionState(nextState);
    // 勾选变化会使导出内容改变，作废进行中的导出意图
    clearPending();
  };

  /** 选择达到上限时的 toast 提示 */
  const handleSelectionLimit = (currentMode: SelectionMode) => {
    if (currentMode === SelectionMode.MANUAL) {
      message.warning(`最多只能勾选 ${MAX_SELECTION} 条数据`);
    } else {
      message.warning(`最多只能反选 ${MAX_SELECTION} 条数据`);
    }
  };

  // ---- 导出逻辑 ----

  /** 计算当前已选条数 */
  const selectedCount =
    selectionState.mode === SelectionMode.MANUAL
      ? selectionState.selectedIds.size
      : Math.max(0, total - selectionState.excludedIds.size);

  /** 点击"导出已选" */
  const handleExportSelected = () => {
    // 新一轮导出意图：作废旧 Key
    clearPending();
    setExportEntry('selected');
    setExportTitle(`导出已选（${selectedCount} 条）`);
    setExportOpen(true);
  };

  /** 点击"导出筛选结果" */
  const handleExportFiltered = () => {
    clearPending();
    setExportEntry('filtered');
    setExportTitle(`导出筛选结果（${total} 条）`);
    setExportOpen(true);
  };

  /** 弹窗确认：拼接导出请求体并异步提交，Promise 成功（含"已有相同导出任务"）时才由父级关弹窗 */
  const handleExportConfirm = async (filename: string, fields: string[]) => {
    // 1. 拼接后端请求体（query 只保留筛选谓词，id 升序）
    const body: ExportJobRequest = { filename: filename.trim(), fields, mode: ExportMode.SELECTED };

    if (exportEntry === 'selected') {
      if (selectionState.mode === SelectionMode.MANUAL) {
        body.mode = ExportMode.SELECTED;
        body.selectedIds = ascSortedIds(selectionState.selectedIds);
      } else {
        body.mode = ExportMode.ALL_EXCLUDE;
        body.excludedIds = ascSortedIds(selectionState.excludedIds);
        body.query = filterQuery(query);
      }
    } else {
      body.mode = ExportMode.FILTERED;
      body.query = filterQuery(query);
    }

    // 2. 幂等 Key：内容未变且上一意图仍在 → 复用；否则重新生成
    const sig = buildSignature(body);
    const pending = pendingRef.current;
    let key: string | null = pending && pending.sig === sig ? pending.key : null;
    if (key === null) {
      key = newIdempotencyKey();
      pendingRef.current = { key, sig };
    }

    try {
      await submitExportJob(body, key);
      message.success('已创建导出任务');
      clearPending();
      setExportOpen(false);
    } catch (err) {
      if (err instanceof ApiError && err.httpStatus === 409) {
        // 两条 409 一律复用后端 message 原文展示（前端不拼接自定义文案，后端 message 即最终文本）
        if (classifyExport409(err.message) === 'duplicate') {
          // 该意图任务先前已创建（如首次成功但响应丢失后的重试），后端并未新建——
          // 展示原文后按成功闭环（中性提示，避免再像"已创建"那样暗示新建）
          message.info(err.message);
          clearPending();
          setExportOpen(false);
          return;
        }
        // conflict：幂等值冲突，属异常生命周期——作废当前 Key（下次确认换新 Key），保留弹窗供重新确认
        message.error(err.message);
        clearPending();
        throw err;
      }
      // 网络失败 / 超时 / 5xx / 400 等：保留 pending，同内容重试复用同一 Key
      message.error(err instanceof Error ? err.message : '导出任务创建失败');
      throw err;
    }
  };

  return (
    <div className={styles.page}>
      <div className={styles.headerRow}>
        <div className={styles.header}>订单管理</div>
        <Space>
          <Button onClick={handleExportSelected} disabled={selectedCount === 0}>
            导出已选{selectedCount > 0 ? `（${selectedCount}）` : ''}
          </Button>
          <Button onClick={handleExportFiltered} disabled={total === 0}>
            导出筛选结果
          </Button>
        </Space>
      </div>
      <OrderQueryForm onSearch={handleSearch} onReset={handleReset} />
      <PageTable<Order>
        rowKey="id"
        columns={columns}
        dataSource={list}
        loading={loading}
        total={total}
        page={page}
        pageSize={pageSize}
        onPageChange={handlePageChange}
        selectionState={selectionState}
        onSelectionChange={handleSelectionChange}
        onSelectionLimit={handleSelectionLimit}
        filteredTotal={total}
      />
      <ExportModal
        open={exportOpen}
        onClose={() => setExportOpen(false)}
        title={exportTitle}
        columnOptions={exportColumnOptions}
        onConfirm={handleExportConfirm}
      />
    </div>
  );
}
