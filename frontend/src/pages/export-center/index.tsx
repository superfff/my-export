import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Segmented, Tag, Progress, Button, message } from 'antd';
import type { TableColumnsType } from 'antd';
import PageTable from '../../components/PageTable';
import { downloadExportJob, fetchExportJobs, retryExportJob } from '../../http/export';
import { saveBlob } from '../../utils/download';
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

/** 进度条刷新间隔（ms）：仅当"待导出/导出中 tab 或列表含 RUNNING/PENDING 行"时轻量轮询 */
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

/**
 * 导出中心：列表查询（状态 tab + 底部分页）+ SUCCESS 下载 / FAILED 重试两个动作；
 * 待导出(PENDING)/导出中(RUNNING) 态下 4s 轮询刷新进度条。
 * columns/两个动作处理器都放组件内：重试成功后必须触发本组件 load 刷新列表。
 */
export default function ExportCenter() {
  const [list, setList] = useState<ExportCenterJob[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [statusTab, setStatusTab] = useState<ExportStatusTab>('ALL');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

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

  // 本轮是否需要轮询：当前 tab 或列表含 RUNNING/PENDING 行时，进度/状态才有机会变化。
  // PENDING 也要盯：重试提交后 job 会 PENDING(≤5s)→RUNNING，若只看 RUNNING，PENDING 期无 tick、RUNNING 到了也没人刷。
  const shouldPoll =
    statusTab === 'RUNNING' ||
    statusTab === 'PENDING' ||
    list.some((job) => job.status === 'RUNNING' || job.status === 'PENDING');
  const shouldPollRef = useRef(shouldPoll);
  useEffect(() => {
    shouldPollRef.current = shouldPoll;
  });

  /** 下载：成功落盘；失败 toast 后端原文。文件可能已被清扫/过期（404），toast 即后端文案 */
  const handleDownload = useCallback(async (job: ExportCenterJob) => {
    try {
      const { blob, filename } = await downloadExportJob(job.id);
      saveBlob(blob, filename ?? job.filename ?? `export_${job.id}.xlsx`);
    } catch (err) {
      message.error(err instanceof Error ? err.message : '下载失败');
    }
  }, []);

  /** 重试：成功后静默刷新当前列表（FAILED 行消失/转 PENDING，RUNNING/PENDING 页续刷到终态）；失败 toast 后端原文（409 如并发双击） */
  const handleRetry = useCallback(
    async (job: ExportCenterJob) => {
      try {
        await retryExportJob(job.id);
        load(false);
      } catch (err) {
        message.error(err instanceof Error ? err.message : '重试失败');
      }
    },
    [load],
  );

  // 列定义依赖组件内的动作处理器（下载/重试），放 useMemo 里；列宽放宽给"下载文件/重试"两个按钮
  const columns = useMemo<TableColumnsType<ExportCenterJob>>(
    () => [
      { title: '任务编号', dataIndex: 'id', width: 120 },
      { title: '文件名', dataIndex: 'filename', width: 240, ellipsis: true },
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
        width: 100,
        render: (_, r) => (
          <Tag color={EXPORT_JOB_STATUS[r.status].color}>{EXPORT_JOB_STATUS[r.status].text}</Tag>
        ),
      },
      {
        key: 'progress',
        title: '进度',
        width: 160,
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
      {
        title: '操作',
        key: 'action',
        width: 130,
        render: (_, r) => {
          if (r.status === 'SUCCESS') {
            return (
              <Button type="link" size="small" onClick={() => handleDownload(r)}>
                下载文件
              </Button>
            );
          }
          if (r.status === 'FAILED') {
            return (
              <Button type="link" size="small" onClick={() => handleRetry(r)}>
                重试
              </Button>
            );
          }
          return null; // PENDING/RUNNING/EXPIRED 无动作
        },
      },
    ],
    [handleDownload, handleRetry],
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
