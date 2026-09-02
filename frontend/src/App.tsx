import { useEffect } from 'react';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import routes, { defaultPath } from './router/routes';
import { useRouter } from './router';
import SiderMenu from './layouts/SiderMenu';
import { useBackendHealth } from './hooks/useBackendHealth';
import styles from './App.module.css';

export default function App() {
  const { path, navigate } = useRouter();
  const matched = routes.find((r) => r.path === path);
  const activePath = matched ? path : defaultPath;
  const Component = matched ? matched.component : routes[0].component;

  // 后端健康检测：每 20 秒轮询 /actuator/health
  const { isHealthy } = useBackendHealth();

  // 未匹配路径（如首次访问 "/"）时，替换为默认路由
  useEffect(() => {
    if (!matched) {
      navigate(defaultPath, { replace: true });
    }
  }, [matched, navigate]);

  return (
    <ConfigProvider locale={zhCN}>
      <div className={styles.app}>
        <SiderMenu routes={routes} activePath={activePath} onNavigate={navigate} isHealthy={isHealthy} />
        <main className={styles.content}>
          <Component />
        </main>
      </div>
    </ConfigProvider>
  );
}
