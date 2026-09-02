import { Menu } from 'antd';
import type { RouteConfig } from '../router/routes';
import styles from './SiderMenu.module.css';

interface SiderMenuProps {
  routes: RouteConfig[];
  activePath: string;
  onNavigate: (path: string) => void;
  /** 后端健康状态 */
  isHealthy?: boolean;
}

export default function SiderMenu({ routes, activePath, onNavigate, isHealthy = true }: SiderMenuProps) {
  const items = routes.map((r) => ({
    key: r.path,
    label: r.name,
    icon: r.icon,
  }));

  return (
    <aside className={styles.sider}>
      <div className={styles.logo}>订单导出后台</div>
      <Menu
        theme="dark"
        mode="inline"
        selectedKeys={[activePath]}
        items={items}
        onClick={({ key }) => onNavigate(key)}
      />
      <div className={`${styles.healthBar} ${isHealthy ? styles.healthy : styles.unhealthy}`}>
        {isHealthy ? '后端服务正常' : '后端服务异常'}
      </div>
    </aside>
  );
}
