import { get, post } from './request';
import type { ExportCenterJob, ExportJobStatus, ExportJobVO, ExportMode, PageResult } from '../types/order';

/** 创建导出任务的请求体（与后端 ExportCreateRequest 对齐，键序固定便于生成 sig） */
export interface ExportJobRequest {
  filename: string;
  fields: string[];
  mode: ExportMode;
  /** FILTERED / ALL_EXCLUDE 的筛选谓词（毫秒时间戳） */
  query?: {
    orderNo?: string;
    customerName?: string;
    phone?: string;
    status?: number;
    startTime?: number;
    endTime?: number;
  };
  /** SELECTED：手动勾选 id */
  selectedIds?: number[];
  /** ALL_EXCLUDE：反选排除 id */
  excludedIds?: number[];
}

/**
 * 创建导出任务。携带 Idempotency-Key 请求头做幂等意图保护。
 * 幂等重复时后端返回真实 HTTP 409（envelope.code=409），由调用方按 message 区分两种语义。
 */
export function submitExportJob(body: ExportJobRequest, idempotencyKey: string): Promise<ExportJobVO> {
  return post<ExportJobVO>('/api/export-job', body, { headers: { 'Idempotency-Key': idempotencyKey } });
}

/** 导出中心分页查询参数：不传 status = 全部（"全部" tab） */
export interface ExportJobListQuery {
  status?: ExportJobStatus;
  page?: number;
  pageSize?: number;
}

/** 导出中心分页查询：不传 status = 全部；默认后端按创建时间降序 */
export function fetchExportJobs(query: ExportJobListQuery = {}): Promise<PageResult<ExportCenterJob>> {
  return get<PageResult<ExportCenterJob>>('/api/export-job', query);
}
