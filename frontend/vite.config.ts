import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// antd v5 使用 CSS-in-JS，无需额外按需引入插件；
// 直接从 antd 具名导入，Vite 会基于 ESM 自动 tree-shaking。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    open: false,
    // 开发环境代理：前端请求 /api、/actuator 转发到本地后端，避免跨域
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
});
