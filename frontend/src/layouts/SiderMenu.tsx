import { Menu } from 'antd';
import type { RouteConfig } from '../router/routes';
import styles from './SiderMenu.module.css';

interface SiderMenuProps {
  routes: RouteConfig[];
  activePath: string;
  onNavigate: (path: string) => void;
}

export default function SiderMenu({ routes, activePath, onNavigate }: SiderMenuProps) {
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
    </aside>
  );
}
