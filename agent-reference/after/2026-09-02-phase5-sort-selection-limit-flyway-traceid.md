# 变更日志 - 2026-09-02 17:00

## 概述

本轮实现了 5.0 需求文档中的全部功能：前端排序 UI + 选择 100 条上限 + 导出按钮条数显示，后端排序/稳定分页/白名单校验 + Flyway 数据库迁移 + traceId 链路追踪基础设施 + JSON envelope 规范化。

## 本次变更的目标

1. 前端筛选条件增加排序功能（按金额/创建时间的升序/降序）
2. 前端手选模式最多勾选 100 条，全选模式最多反选 100 条，达到上限 toast 提示
3. 前端导出已选按钮显示选中条数
4. 后端实现稳定分页（排序值相同时以 id 为第二排序键）
5. 后端排序参数白名单校验，非法值返回清晰错误文案
6. 后端引入 Flyway 管理数据库版本迁移，DDL 与 DML 分离
7. 后端 JSON envelope 增加 traceId 字段，TraceIdFilter 实现日志链路追踪基础
8. 全局异常处理器，确保业务异常也返回规范 ApiResponse

## 变更详情

### backend/pom.xml

- **修改类型**：修改
- **修改内容**：添加 `flyway-core` 和 `flyway-mysql` 两个依赖
- **修改原因**：引入 Flyway 作为数据库版本迁移工具，后续 schema 变更通过 `db/migration/V{N}__xxx.sql` 管理，不再手动修改初始化脚本

### backend/src/main/resources/application.yml

- **修改类型**：修改
- **修改内容**：添加 `spring.flyway` 配置段（enabled=true、locations、baseline-on-migrate=true）
- **修改原因**：启用 Flyway 并配置迁移脚本路径；`baseline-on-migrate=true` 避免首次在已有数据库上启用 Flyway 时因缺少 `flyway_schema_history` 表而报错

### backend/src/main/resources/db/migration/V1__create_t_order.sql

- **修改类型**：新增
- **修改内容**：t_order 表的 DDL（CREATE TABLE），不含任何种子数据
- **修改原因**：Flyway V1 基线迁移脚本，将表结构纳入版本管理。后续 schema 变更只需新增 V2、V3……脚本

### docker/mysql/init/01-init.sql

- **修改类型**：修改
- **修改内容**：仅保留 `SET NAMES utf8mb4` + `CREATE DATABASE` + `USE`，删除了 CREATE TABLE 和 30 条 INSERT 语句
- **修改原因**：DDL 已迁移至 Flyway 管理，Docker 初始化脚本只负责建库；种子数据由单独的脚本提供，保持空表

### scripts/10000-test-data.sql

- **修改类型**：新增
- **修改内容**：MySQL 存储过程，使用 WHILE 循环 + RAND() 生成 10000 条有规律但随机的订单数据（订单号按日期递增、姓名/手机号随机组合、状态 40%/40%/20% 分布、金额 10~2000、时间覆盖 2026-08 全月、15% 有备注）
- **修改原因**：提供独立的测试数据脚本，不属于 Flyway 迁移链，需要时手动执行。放在根目录 `scripts/` 与 `backend/`、`frontend/` 并行，不影响 Flyway 版本线

### backend/src/main/java/com/example/order/common/ApiResponse.java

- **修改类型**：修改
- **修改内容**：record 增加 `traceId` 字段；新增 ThreadLocal 持有当前请求的 traceId；`ok()` 和 `error()` 方法自动从 ThreadLocal 读取 traceId 写入响应
- **修改原因**：JSON envelope 规范化，所有接口响应统一携带 traceId，供前端和日志系统做链路追踪

### backend/src/main/java/com/example/order/common/TraceIdFilter.java

- **修改类型**：新增
- **修改内容**：OncePerRequestFilter 实现，检查 MDC 中是否有 traceId → 有则复用，无则生成 UUID → 写入 MDC + ApiResponse ThreadLocal + 响应头 `x-trace-id`；finally 中清理防止线程泄漏
- **修改原因**：为每个 HTTP 请求自动绑定 traceId，实现日志链路追踪的基础设施。MDC 存储使 logback 等日志框架可通过 `%X{traceId}` 输出；响应头让前端/网关也能获取

### backend/src/main/java/com/example/order/config/FilterConfig.java

- **修改类型**：新增
- **修改内容**：注册 TraceIdFilter 为 Servlet Filter，URL 匹配 `/*`，优先级 order=1
- **修改原因**：将 TraceIdFilter 注册到 Spring Boot Filter 链，且设为最高优先级，确保所有请求在进入业务逻辑前就绑定 traceId

### backend/src/main/java/com/example/order/common/GlobalExceptionHandler.java

- **修改类型**：新增
- **修改内容**：@RestControllerAdvice，捕获 IllegalArgumentException 返回 `ApiResponse.error(400, message)`
- **修改原因**：排序白名单校验抛出 IllegalArgumentException 后，如果没有全局异常处理器，Spring 会返回 HTTP 500 + 默认错误体，前端无法拿到清晰错误文案。此处理器确保业务异常也遵循 JSON envelope 规范（包含 code、message、traceId）

### backend/src/main/java/com/example/order/common/SortParamValidator.java

- **修改类型**：新增
- **修改内容**：排序参数白名单校验工具类，ALLOWED_FIELDS = {amount, createdAt}，ALLOWED_ORDERS = {asc, desc}；校验逻辑包括：同时为空合法（走默认排序）、只传一个报错、非法值报错并列出合法值
- **修改原因**：防止前端或调用方传入非法排序参数（如 sortField=password），避免 SQL 注入风险和不可控查询行为；同时提供清晰的错误文案

### backend/src/main/java/com/example/order/dto/OrderQueryDTO.java

- **修改类型**：修改
- **修改内容**：record 新增 `sortField`（String）和 `sortOrder`（String）两个参数
- **修改原因**：后端需要接收前端传来的排序字段和方向参数，用于动态排序

### backend/src/main/java/com/example/order/service/impl/OrderServiceImpl.java

- **修改类型**：修改
- **修改内容**：1) page() 方法开头调用 SortParamValidator.validate()，不合法则抛 IllegalArgumentException；2) 排序逻辑从硬编码 `orderByDesc(createdAt)` 改为根据 sortField/sortOrder 动态排序（amount/createdAt × asc/desc）；3) 始终追加 `orderByAsc(id)` 作为第二排序键实现稳定分页
- **修改原因**：1) 白名单校验防止非法参数；2) 动态排序支持前端排序 UI；3) 稳定分页避免排序值相同时分页结果漂移（同金额/同时间的记录可能在不同页间跳动）

### frontend/src/types/order.ts

- **修改类型**：修改
- **修改内容**：1) 新增 `SortField = 'amount' | 'createdAt'` 和 `SortOrder = 'asc' | 'desc'` 类型；2) `OrderQuery` 增加 `sortField?` 和 `sortOrder?`；3) `ApiResponse<T>` 增加 `traceId?: string`
- **修改原因**：前端类型与后端接口对齐：排序参数需要类型约束；traceId 是后端 envelope 新增字段

### frontend/src/http/request.ts

- **修改类型**：修改
- **修改内容**：`RawResponse<T>` 接口增加 `traceId?: string` 字段
- **修改原因**：底层 fetch 封装的响应类型需要与后端 JSON envelope 结构保持一致

### frontend/src/constants/order.ts

- **修改类型**：修改
- **修改内容**：新增 `MAX_SELECTION = 100` 常量
- **修改原因**：集中管理选择上限值，PageTable 和页面层共用，便于后续调整

### frontend/src/pages/order/OrderQueryForm.tsx

- **修改类型**：修改
- **修改内容**：1) `OrderFormValues` 增加 `sortField?` 和 `sortOrder?`；2) 表单新增两个 Select：排序字段（创建时间/金额）和排序方向（升序/降序），均 allowClear
- **修改原因**：前端排序 UI 需求，用户可选择按哪个字段、什么方向排序；allowClear 时走后端默认排序

### frontend/src/components/PageTable/index.tsx

- **修改类型**：修改
- **修改内容**：1) Props 新增 `onSelectionLimit` 回调；2) 手动模式下 `handleRowSelect` 检查 `selectedIds.size >= MAX_SELECTION`，达到上限阻止勾选并调用回调；3) 全选模式下 `handleRowSelect` 检查 `excludedIds.size >= MAX_SELECTION`，达到上限阻止反选并调用回调；4) `handleHeaderCheck` 同样受上限约束；5) `handleModeChange` 切换到全选时清空 selectedIds；6) 提示语改为 `已勾选 N/100 条` / `排除 N/100 条`
- **修改原因**：选择上限需求——手动模式最多勾 100 条、全选模式最多反选 100 条；切换模式时清空手动选择的数据；提示语体现上限信息

### frontend/src/pages/order/index.tsx

- **修改类型**：修改
- **修改内容**：1) 新增 `handleSelectionLimit` 回调，根据模式调用 `message.warning` 显示 toast；2) 将 `onSelectionLimit` 传给 PageTable；3) 导出已选按钮文案改为 `导出已选（N）`（有选中时显示条数，无选中时不显示括号）
- **修改原因**：1-2) 选择达到上限时用户需要可见的 toast 提示；3) 导出按钮显示条数让用户确认将导出的数据量

## 关联说明

1. **Flyway 全链路**：`pom.xml`（依赖）→ `application.yml`（配置）→ `V1__create_t_order.sql`（迁移脚本）→ `01-init.sql`（简化为仅建库）→ `scripts/10000-test-data.sql`（独立测试数据）。Flyway 管理的只是 DDL 版本线，测试数据脚本在版本线之外。

2. **traceId 全链路**：`TraceIdFilter`（生成/复用 traceId，写 MDC + ThreadLocal + 响应头）→ `FilterConfig`（注册 Filter）→ `ApiResponse`（从 ThreadLocal 读取并写入 JSON envelope）→ `request.ts` / `types/order.ts`（前端类型对齐）。后续 logback 配置 `%X{traceId}` 即可在日志中输出。

3. **排序全链路**：`OrderQueryDTO`（接收参数）→ `SortParamValidator`（白名单校验）→ `OrderServiceImpl`（动态排序 + 稳定分页）→ `GlobalExceptionHandler`（校验失败的规范错误响应）→ `types/order.ts`（SortField/SortOrder 类型）→ `OrderQueryForm.tsx`（排序 UI）→ `pages/order/index.tsx`（参数透传）。前后端联动，参数名 sortField/sortOrder 完全一致。

4. **选择上限**：`constants/order.ts`（MAX_SELECTION）→ `PageTable`（校验逻辑 + 提示语 + 模式切换清空）→ `pages/order/index.tsx`（onSelectionLimit → message.warning toast）。上限值集中管理，组件通过回调通知页面层展示 toast。
