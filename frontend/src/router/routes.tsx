import type { ComponentType, ReactNode } from 'react';
import { ExportOutlined, FileTextOutlined } from '@ant-design/icons';
import OrderList from '../pages/order';
import ExportCenter from '../pages/export-center';

export interface RouteConfig {
  path: string;
  name: string;
  icon: ReactNode;
  component: ComponentType;
}

// 路由为唯一数据源，同时供左侧目录与页面切换使用
const routes: RouteConfig[] = [
  { path: '/order', name: '订单管理', icon: <FileTextOutlined />, component: OrderList },
  { path: '/export-center', name: '导出中心', icon: <ExportOutlined />, component: ExportCenter },
];

export const defaultPath = routes[0].path;

export default routes;
