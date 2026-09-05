import { useCallback, useEffect, useRef, useState } from 'react';
import { Segmented, Tag, Progress } from 'antd';
import type { TableColumnsType } from 'antd';
import PageTable from '../../components/PageTable';
import { fetchExportJobs } from '../../http/export';
import {
  EXPORT_JOB_STATUS,
  EXPORT_JOB_STATUS_TABS,
  EXPORT_MODE_TEXT,
  exportProgressPercent,
} from '../../constants/export';
import type { ExportStatusTab } from '../../constants/export';
import type { ExportCenterJob } from '../../types/order';
import { formatDateTime } from '../../utils/format';
import styles from './index.module.css';

const DEFAULT_PAGE_SIZE = 20;

/** 进度条刷新间隔（ms）：仅当"导出中 tab 或列表含 RUNNING 行"时轻量轮询 */
const POLL_INTERVAL = 4000;

/**
 * 进度百分比：基于 processedRows/expectedTotal（SUCCESS→100，否则封顶 99，避免 RUNNING 误显示 100）；
 * expectedTotal<=0 无法计算时返回 null → 显示 '-'。
 */
function resolveExportProgress(job: ExportCenterJob): number | null {
  return exportProgressPercent({
    status: job.status,
    processedRows: job.processedRows ?? 0,
    expectedTotal: job.expectedTotal ?? 0,
  });
}

const columns: TableColumnsType<ExportCenterJob> = [
  { title: '任务编号', dataIndex: 'id', width: 120 },
  { title: '文件名', dataIndex: 'filename', width: 260, ellipsis: true },
  {
    title: '导出范围',
    dataIndex: 'exportMode',
    width: 140,
    render: (_, r) => EXPORT_MODE_TEXT[r.exportMode] ?? r.exportMode,
  },
  {
    title: '导出统计条数',
    dataIndex: 'expectedTotal',
    width: 130,
    align: 'right',
    render: (_, r) => (r.expectedTotal ?? 0).toLocaleString(),
  },
  {
    title: '导出实际条数',
    dataIndex: 'processedRows',
    width: 130,
    align: 'right',
    render: (_, r) => (r.processedRows == null ? '-' : r.processedRows.toLocaleString()),
  },
  {
    title: '状态',
    dataIndex: 'status',
    width: 110,
    render: (_, r) => (
      <Tag color={EXPORT_JOB_STATUS[r.status].color}>{EXPORT_JOB_STATUS[r.status].text}</Tag>
    ),
  },
  {
    key: 'progress',
    title: '进度',
    width: 180,
    render: (_, r) => {
      const p = resolveExportProgress(r);
      return p == null ? '-' : <Progress percent={p} size="small" />;
    },
  },
  { title: '创建时间', dataIndex: 'createdAt', width: 180, render: (_, r) => formatDateTime(r.createdAt) },
  { title: '完成时间', dataIndex: 'finishedAt', width: 180, render: (_, r) => formatDateTime(r.finishedAt) },
  {
    title: '文件大小',
    dataIndex: 'fileSize',
    width: 110,
    render: (_, r) => (r.fileSize == null ? '-' : `${r.fileSize} B`),
  },
];

/** 导出中心：只读列表，无任何按钮操作；状态 tab + 底部分页；"导出中"态下 4s 轮询刷新进度条 */
export default function ExportCenter() {
  const [list, setList] = useState<ExportCenterJob[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [statusTab, setStatusTab] = useState<ExportStatusTab>('ALL');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [hasRunning, setHasRunning] = useState(false);

  // 本轮是否需要轮询：仅当前 tab 为"导出中"或列表含 RUNNING 行时，进度才有机会变化
  const shouldPoll =
    statusTab === 'RUNNING' || hasRunning || list.some((job) => job.status === 'RUNNING');
  const shouldPollRef = useRef(shouldPoll);
  useEffect(() => {
    shouldPollRef.current = shouldPoll;
  });

  // 依 statusTab / page / pageSize 拉取；返回局部取消函数，重入时先作废旧请求防竞态
  const load = useCallback(
    (showLoading: boolean) => {
      let cancelled = false;
      if (showLoading) setLoading(true);
      fetchExportJobs({ status: statusTab === 'ALL' ? undefined : statusTab, page, pageSize })
        .then((result) => {
          if (cancelled) return;
          setList(result.list);
          setTotal(result.total);
          setHasRunning(result.list.some((job) => job.status === 'RUNNING'));
        })
        .catch((err) => {
          if (!cancelled) console.error('查询导出任务失败：', err);
        })
        .finally(() => {
          if (!cancelled && showLoading) setLoading(false);
        });
      return () => {
        cancelled = true;
      };
    },
    [statusTab, page, pageSize],
  );

  // 首查 / tab / 分页变化时加载
  useEffect(() => load(true), [load]);

  // 轻量轮询：静默刷新（不动 loading，避免进度条闪烁），仅在需要时触发
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (shouldPollRef.current) load(false);
    }, POLL_INTERVAL);
    return () => window.clearInterval(timer);
  }, [load]);

  // tab 切换回到第 1 页
  const handleTabChange = (value: ExportStatusTab | number | string) => {
    setStatusTab(value as ExportStatusTab);
    setPage(1);
  };

  // 换页改 page；改每页条数回第 1 页（翻页不重置 tab）
  const handlePageChange = (p: number, ps: number) => {
    setPage(p);
    setPageSize(ps);
  };

  return (
    <div className={styles.page}>
      <div className={styles.headerRow}>
        <div className={styles.header}>导出中心</div>
        <Segmented
          value={statusTab}
          onChange={handleTabChange}
          options={EXPORT_JOB_STATUS_TABS.map((t) => ({ value: t.key, label: t.label }))}
        />
      </div>
      {/* 只读列表：不传选择 props，走 PageTable 无选择分支 */}
      <PageTable<ExportCenterJob>
        rowKey="id"
        columns={columns}
        dataSource={list}
        loading={loading}
        total={total}
        page={page}
        pageSize={pageSize}
        onPageChange={handlePageChange}
      />
    </div>
  );
}
