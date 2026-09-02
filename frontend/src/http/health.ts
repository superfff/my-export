/**
 * 后端健康检查请求。
 * 直接请求 /actuator/health，该接口返回 Actuator 原始 JSON（非 ApiResponse 包装），
 * 因此不走 http/request.ts 的统一响应解析，单独用 fetch 处理。
 */

interface HealthResponse {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN';
}

/**
 * 检查后端健康状态。
 * @returns true 表示后端正常（UP），false 表示异常或请求失败
 */
export async function checkBackendHealth(): Promise<boolean> {
  try {
    const res = await fetch('/actuator/health', {
      headers: { 'Content-Type': 'application/json' },
    });
    if (!res.ok) return false;
    const body = (await res.json()) as HealthResponse;
    return body.status === 'UP';
  } catch {
    return false;
  }
}
