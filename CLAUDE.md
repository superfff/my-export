# 订单导出后台（全栈）

单仓库结构：`frontend/` 前端 + `backend/` 后端 + `docker-compose.yml` 一键部署。

## 前端代码规范（frontend/）
1. 使用清晰的文件夹目录结构，例如有涉及到重复的业务组件，使用 `/components/` 文件夹，如果有重复的代码，提取到 `/utils`
2. 使用 pnpm 进行依赖包的管理
3. typescript 维护清晰的 `interface` 或者 `type`，减少使用 **any**
4. 接口请求统一放在 `src/http/`：`request.ts` 为底层 fetch 封装，业务接口按模块另建文件调用，保持低耦合

## 后端代码规范（backend/）
1. 使用 Spring Boot + Maven，Java 21
2. MVC 分层：`controller`（接口）→ `service`（业务）→ `mapper`（数据访问），DTO 放在 `dto/` 包
3. 接口统一前缀 `/api`，健康检查 `/actuator/health`
4. 数据访问层使用 MyBatis-Plus，数据库为 MySQL

## 数据库
- 库名 `order_db`，表 `t_order`；初始化脚本在 `docker/mysql/init/01-init.sql`
