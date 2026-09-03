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

/**
 * 统一错误类型：携带后端返回的 httpStatus / code / message / traceId。
 * 非 2xx 但响应体不是 envelope（如网关层错误）时 code 退化为 httpStatus。
 */
export class ApiError extends Error {
  readonly httpStatus: number;
  readonly code: number;
  readonly traceId?: string;

  constructor(message: string, { httpStatus, code, traceId }: { httpStatus: number; code: number; traceId?: string }) {
    super(message);
    this.name = 'ApiError';
    this.httpStatus = httpStatus;
    this.code = code;
    this.traceId = traceId;
  }
}

/** 判断响应体是否为后端统一 envelope（有数值 code 字段） */
function isEnvelope(body: unknown): body is RawResponse<unknown> {
  return typeof body === 'object' && body !== null && typeof (body as { code?: unknown }).code === 'number';
}

/** 发起请求并解析后端统一响应：任意状态都尝试解析 envelope，非 0 code 抛 ApiError */
export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = { 'Content-Type': 'application/json', ...(options.headers as Record<string, string> | undefined) };
  const res = await fetch(path, { ...options, headers });

  let body: unknown = null;
  try {
    body = await res.json();
  } catch {
    // 空响应体 / 非 JSON：留在 body = null，由下面分支兜底
  }

  if (isEnvelope(body)) {
    if (!res.ok || body.code !== 0) {
      throw new ApiError(body.message || '请求失败', {
        httpStatus: res.status,
        code: body.code,
        traceId: body.traceId,
      });
    }
    return body.data as T;
  }

  // 非 2xx 且响应体非 envelope：保底报错
  if (!res.ok) {
    throw new ApiError(`请求失败：HTTP ${res.status}`, { httpStatus: res.status, code: res.status });
  }
  return body as T;
}

/** GET 请求，自动拼接 query */
export function get<T>(path: string, params?: object): Promise<T> {
  return request<T>(`${path}${buildQuery(params)}`);
}

/** POST 请求，JSON 序列化 body，可透传额外请求头（如 Idempotency-Key） */
export function post<T>(path: string, body?: object, options: RequestInit = {}): Promise<T> {
  return request<T>(path, { method: 'POST', body: JSON.stringify(body ?? {}), ...options });
}
