/**
 * 底层 fetch 封装：负责拼接 query、发起请求、解析 JSON、统一错误处理。
 * 业务请求（如订单查询）应在同目录下另建模块调用这里，保持低耦合。
 */

/** 拼接 query 字符串，忽略空值（undefined / null / ''） */
function buildQuery(params?: object): string {
  if (!params) return '';
  const entries = Object.entries(params as Record<string, unknown>);
  const parts = entries
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 请求成功后的后端统一响应结构 */
interface RawResponse<T> {
  code: number;
  message?: string;
  data: T;
  /** 日志链路追踪 ID，由后端 TraceIdFilter 注入 */
  traceId?: string;
}

/** 发起请求并解析后端统一响应，code !== 0 时抛错 */
export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });

  if (!res.ok) {
    throw new Error(`请求失败：HTTP ${res.status}`);
  }

  const body = (await res.json()) as RawResponse<T>;
  if (body.code !== 0) {
    throw new Error(body.message || '请求失败');
  }
  return body.data;
}

/** GET 请求，自动拼接 query */
export function get<T>(path: string, params?: object): Promise<T> {
  return request<T>(`${path}${buildQuery(params)}`);
}
