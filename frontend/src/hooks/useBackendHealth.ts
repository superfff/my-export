import { useEffect, useRef, useState } from 'react';
import { checkBackendHealth } from '../http/health';

/** 健康检查轮询间隔（毫秒） */
const HEALTH_CHECK_INTERVAL = 20000;

/**
 * 后端健康检测 Hook。
 * 每 20 秒请求 /actuator/health，返回后端是否健康。
 * 组件卸载时自动清理定时器。
 */
export function useBackendHealth(): { isHealthy: boolean } {
  const [isHealthy, setIsHealthy] = useState(true);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    // 首次立即检查一次
    checkBackendHealth().then(setIsHealthy);

    timerRef.current = setInterval(async () => {
      const healthy = await checkBackendHealth();
      setIsHealthy(healthy);
    }, HEALTH_CHECK_INTERVAL);

    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
    };
  }, []);

  return { isHealthy };
}
