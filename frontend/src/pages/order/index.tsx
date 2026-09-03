import { useCallback, useEffect, useState } from 'react';
import { Tag, Button, Space, message } from 'antd';
import type { TableColumnsType } from 'antd';
import PageTable from '../../components/PageTable';
import { createInitialSelectionState } from '../../components/PageTable';
import ExportModal from '../../components/ExportModal';
import OrderQueryForm from './OrderQueryForm';
import type { OrderFormValues } from './OrderQueryForm';
import { fetchOrders } from '../../http/order';
import { ORDER_STATUS, MAX_SELECTION } from '../../constants/order';
import type { Order, OrderQuery, OrderStatus, SelectionState, ExportParams } from '../../types/order';
import { ExportMode, SelectionMode } from '../../types/order';
import { formatDateTime } from '../../utils/format';
import styles from './index.module.css';

const DEFAULT_PAGE_SIZE = 20;

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

export default function OrderList() {
  const [list, setList] = useState<Order[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<OrderQuery>({});
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
  };

  const handleReset = () => {
    setQuery({});
    setPage(1);
    // 重置时清空勾选
    setSelectionState(createInitialSelectionState());
  };

  const handlePageChange = (p: number, ps: number) => {
    setPage(p);
    setPageSize(ps);
  };

  const handleSelectionChange = (nextState: SelectionState) => {
    setSelectionState(nextState);
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
    setExportEntry('selected');
    setExportTitle(`导出已选（${selectedCount} 条）`);
    setExportOpen(true);
  };

  /** 点击"导出筛选结果" */
  const handleExportFiltered = () => {
    setExportEntry('filtered');
    setExportTitle(`导出筛选结果（${total} 条）`);
    setExportOpen(true);
  };

  /** 弹窗确认：拼接导出参数 */
  const handleExportConfirm = (filename: string, fields: string[]) => {
    const params: ExportParams = { filename, fields, mode: ExportMode.SELECTED };

    if (exportEntry === 'selected') {
      if (selectionState.mode === SelectionMode.MANUAL) {
        params.mode = ExportMode.SELECTED;
        params.selectedIds = Array.from(selectionState.selectedIds);
      } else {
        params.mode = ExportMode.ALL_EXCLUDE;
        params.excludedIds = Array.from(selectionState.excludedIds);
        params.query = query;
      }
    } else {
      params.mode = ExportMode.FILTERED;
      params.query = query;
    }

    // 暂不发送后端请求，仅输出拼接的参数
    console.log('导出参数：', params);
    setExportOpen(false);
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
