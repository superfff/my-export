import { useEffect, useState } from 'react';
import { Segmented, Tag, Progress } from 'antd';
import type { TableColumnsType } from 'antd';
import PageTable from '../../components/PageTable';
import { fetchExportJobs } from '../../http/export';
import { EXPORT_JOB_STATUS, EXPORT_JOB_STATUS_TABS, EXPORT_MODE_TEXT } from '../../constants/export';
import type { ExportStatusTab } from '../../constants/export';
import type { ExportCenterJob } from '../../types/order';
import { formatDateTime } from '../../utils/format';
import styles from './index.module.css';

const DEFAULT_PAGE_SIZE = 20;

/** 进度 0-100：优先用后端返回 progress；否则按 actualTotal/expectedTotal 推导；都缺省返回 null → 显示 '-' */
function resolveExportProgress(job: ExportCenterJob): number | null {
  if (job.progress != null) return Math.min(100, Math.max(0, Math.round(job.progress)));
  if (job.actualTotal != null && job.expectedTotal > 0) {
    return Math.min(100, Math.max(0, Math.round((job.actualTotal / job.expectedTotal) * 100)));
  }
  return null;
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
    dataIndex: 'actualTotal',
    width: 130,
    align: 'right',
    render: (_, r) => (r.actualTotal == null ? '-' : r.actualTotal.toLocaleString()),
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
    title: '进度',
    dataIndex: 'progress',
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

/** 导出中心：只读列表，无任何按钮操作；状态 tab + 底部分页 */
export default function ExportCenter() {
  const [list, setList] = useState<ExportCenterJob[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [statusTab, setStatusTab] = useState<ExportStatusTab>('ALL');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  // 依 statusTab / page / pageSize 拉取；局部取消标志防快速切换竞态
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    fetchExportJobs({ status: statusTab === 'ALL' ? undefined : statusTab, page, pageSize })
      .then((result) => {
        if (cancelled) return;
        setList(result.list);
        setTotal(result.total);
      })
      .catch((err) => {
        if (!cancelled) console.error('查询导出任务失败：', err);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [statusTab, page, pageSize]);

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
