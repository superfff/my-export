# 变更日志 - 2026-09-03 20:38

## 概述

完成阶段 6.0「异步导出任务发起 + 持久化 + 幂等去重」的全栈代码改造：把订单页原来只在 `console.log` 的导出动作，改造成真实的「前端异步提交 → 后端校验 → `export_jobs` 入库」链路。新增独立 RESTful 接口 `POST /api/export-job`（**不沿用 / 不调用** `GET /api/order`），任务入库时按"本次会导出的行集合"实时聚合出两个快照列（`expected_total` 命中总数、`max_order_id` 命中订单最大 `t_order.id`）。本期不做真正导出、不推进任务状态（恒 `PENDING`）。

## 本次变更的目标

1. 新增 `POST /api/export-job`，以幂等键 `Idempotency-Key` 保护"一次导出意图"，同 Key 重复提交返回真实 HTTP 409
2. `export_jobs` 表承载任务：幂等键唯一索引 + `request_hash` 内容指纹索引 + 两个创建时聚合的快照列
3. 校验失败返回真实 HTTP 400，409/400 走信封 `{code,message,data,traceId}`；**不得回归**现有 `IllegalArgumentException → HTTP 200 + code=400` 的行为
4. 前端补齐 `post` 与信封错误解析，实现幂等 Key 生命周期（同内容重试复用 Key、内容/筛选/勾选变化作废旧 Key），并区分两种 409 语义
5. 后端 curl 矩阵与前端 typecheck/lint/test/build 全量验证通过

## 变更详情

### backend/src/main/resources/db/migration/V2__create_export_jobs.sql
- **修改类型**：新增
- **修改内容**：Flyway V2 建 `export_jobs` 表：`idempotency_key`（唯一键 `uk_idempotency_key`）、`request_hash`（`CHAR(64)` 普通索引）、`export_mode`/`status`/`filename`/`export_columns`/`scope_snapshot`，以及两个快照列 `expected_total`、`max_order_id`（均 `BIGINT UNSIGNED`）
- **修改原因**：给导出任务落地存储。两个快照列是需求新增口径——建任务时就算好"当前查询条件下命中总条数 / 最大 jobId（订单 id）"，为后续导出中心展示与真正导出时做进度分母、防漂移水位打前站

### backend/.../enums/ExportMode.java
- **修改类型**：新增
- **修改内容**：导出模式枚举 `SELECTED / ALL_EXCLUDE / FILTERED`，对应前端手动勾选 / 全选反选 / 导出筛选结果
- **修改原因**：请求契约里 `mode` 是枚举值，后端需要一份类型化定义做校验与分支

### backend/.../enums/ExportJobStatus.java
- **修改类型**：新增
- **修改内容**：状态机枚举 `PENDING / RUNNING / SUCCESS / FAILED`
- **修改原因**：`RUNNING/SUCCESS/FAILED` 只作为合法枚举存在，本期插入恒为 `PENDING`、无任何推进路径

### backend/.../entity/ExportJob.java
- **修改类型**：新增
- **修改内容**：`export_jobs` 表实体，手写 getter/setter；`export_mode`/`status` 用 `String` 存库，两快照列为 `Long`
- **修改原因**：避免让 MyBatis-Plus 对枚举字段做类型处理器假设；枚举只在 DTO/VO 边界用 `name()/valueOf` 转换，最小化映射风险

### backend/.../mapper/ExportJobMapper.java
- **修改类型**：新增
- **修改内容**：`@Mapper interface ExportJobMapper extends BaseMapper<ExportJob>{}`
- **修改原因**：镜像现有 `OrderMapper` 写法，获得单表 CRUD（本阶段只用 insert/selectById/selectOne）

### backend/.../common/ExportBizException.java
- **修改类型**：新增
- **修改内容**：业务异常，携带 `int httpStatus` + 文案
- **修改原因**：现有全局异常只有一个 IAE→HTTP 200 的处理器，无法表达真实 400/409。用专用异常把"该返回什么 HTTP 状态"显式带出去

### backend/.../common/GlobalExceptionHandler.java
- **修改类型**：修改
- **修改内容**：追加两个 handler——`ExportBizException` → 按异常携带状态返回真实 HTTP；`HttpMessageNotReadableException`（JSON 解析失败、mode 取值非法）→ 信封化 400；原 `IllegalArgumentException` handler 原样保留
- **修改原因**：409/400 需要真实 HTTP 状态码，同时**不许回归**既有 `GET /api/order` 的"HTTP 200 + code=400"语义，所以用独立异常走独立 handler 而不是改动旧逻辑

### backend/.../mapper/OrderMapper.java
- **修改类型**：修改
- **修改内容**：新增聚合方法，单条 SQL 同时取 `COUNT(*)` 与 `COALESCE(MAX(id),0)`，条件用 `${ew.customSqlSegment}` 复用外层拼好的查询包装器
- **修改原因**：需求明确"建任务时对订单库做一次只读聚合"，且**不能调 HTTP `GET /api/order` 列表接口**；单语句聚合保证 count 与 max 取自同一时刻一致快照

### backend/.../dto/ExportCreateRequest.java
- **修改类型**：新增
- **修改内容**：请求体 record（`filename/fields/mode/query/selectedIds/excludedIds`），嵌套 `Query` 承载六键筛选谓词
- **修改原因**：给 `/api/export-job` 定义类型化入参；多余字段靠 Jackson 默认忽略，不做事事强校验

### backend/.../dto/ExportJobVO.java
- **修改类型**：新增
- **修改内容**：创建成功返回的视图对象，含 `id/filename/exportMode/status/expectedTotal/maxOrderId/createdAt`
- **修改原因**：把两个快照列返回给前端展示/后续使用

### backend/.../service/ExportJobService.java
- **修改类型**：新增
- **修改内容**：业务接口，定义 `create(request, idempotencyKey)` 返回 VO
- **修改原因**：MVC 分层约定，controller→service→mapper，接口与实现分离

### backend/.../service/impl/ExportJobServiceImpl.java
- **修改类型**：新增
- **修改内容**：核心实现：校验（幂等键长度、文件名、字段白名单、mode、id 上限 100）→ 算 `request_hash`（`sha256(fieldsCsv#scope)`，范围串按真实 mode 规范化）→ 用同一套范围谓词聚合出 `expected_total/max_order_id` → insert（状态 PENDING）→ 捕获 `DuplicateKeyException` 后重查已存在行、比对 `request_hash` 判别两种 409
- **修改原因**：幂等去重采用"直接 insert + 唯一索引兜底"——比先查后插少一次往返且能覆盖并发；两快照列不参与 hash、不参与幂等判别（需求明确）；早期版本踩过一个坑：id 校验没按 mode 隔离，导致 FILTERED 请求误报"缺少 selectedIds"，已修复为只对对应 mode 校验

### backend/.../controller/ExportJobController.java
- **修改类型**：新增
- **修改内容**：`@RestController @RequestMapping("/api/export-job")`，`POST` 返回 `ResponseEntity<ApiResponse<ExportJobVO>>`；幂等键头置 `required=false`，缺失/超长交给 Service 给清晰 400
- **修改原因**：独立 RESTful 资源、不沿用 `/api/order`；`required=false` 是避免 Spring 默认抛非信封格式的 400，保证错误响应也统一走 envelope

### frontend/src/http/request.ts
- **修改类型**：修改
- **修改内容**：① 修复 headers 合并——自定义请求头传入时不再丢掉 `Content-Type`；② 新增 `ApiError`（携带 httpStatus/code/message/traceId）；③ 任意状态都尝试解析信封、非 0 code 抛 `ApiError`；④ 新增 `post<T>`
- **修改原因**：导出要发 POST + 自定义 `Idempotency-Key` 头，且 409/400 都带信封，旧代码"非 2xx 一律抛纯文案 Error"拿不到 code/状态来区分语义。保留对 GET 调用方的兼容（文案不变）

### frontend/src/http/export.ts
- **修改类型**：新增
- **修改内容**：`submitExportJob(body, idempotencyKey)` → `post('/api/export-job', ...)` 携带幂等键头
- **修改原因**：接口请求按模块低耦合组织（约定：业务接口调用 http 封装），与订单查询文件并列

### frontend/src/types/order.ts
- **修改类型**：修改
- **修改内容**：新增 `ExportJobStatus` 类型与 `ExportJobVO` 接口
- **修改原因**：前端需要对应后端的任务视图类型，展示与后续开发用

### frontend/src/constants/export.ts
- **修改类型**：新增
- **修改内容**：`EXPORT_409_MESSAGES` 常量，两条 409 文案与后端逐字一致
- **修改原因**：前端判定"已有相同导出任务 = 成功"依赖精确匹配文案，抽取常量避免手写散落、前后端口径漂移

### frontend/src/components/ExportModal/index.tsx
- **修改类型**：修改
- **修改内容**：`onConfirm` 改为返回 `Promise`；新增 `confirmLoading` 状态并在提交期间置 loading；提交成功后 `resetFields()`，失败时保留表单值
- **修改原因**：让 OK 按钮有加载反馈；失败时保留用户输入供重试；只有父级判定"成功（含已有相同导出任务）"才会关弹窗并清表单

### frontend/src/components/ExportModal/__tests__/ExportModal.test.tsx
- **修改类型**：修改
- **修改内容**：`onConfirm` 字面量 `() => {}` 改为 `async () => {}`（约 5 处）
- **修改原因**：`onConfirm` 类型改为异步后测试桩也要同步，否则 typecheck 不通过

### frontend/src/pages/order/index.tsx
- **修改类型**：修改
- **修改内容**：删除 `console.log('导出参数')`；实现异步 `handleExportConfirm`：按入口拼装规范请求体（query 只留六键、id 升序）→ 幂等 Key 生命周期（`useRef` 持 `{key, sig}`，同内容失败重试复用 Key、内容变化/筛选/勾选/重开弹窗作废旧 Key）→ 提交后按结果分支：成功关弹窗；409 文案命中"已有相同导出任务"当成功闭环、命中"幂等值冲突"废弃 Key 弹窗留开、其余错误保留 pending 供重试
- **修改原因**：这是需求落地的最后一环——前端要能区分"重复提交=其实成功"与"真冲突"；Key 生命周期保证响应丢失重试不产生重复任务

## 关联说明

- **接口契约闭环**：`ExportJobController`(POST /api/export-job) → `ExportJobService(Impl)` → `ExportJobMapper`(insert/select) + `OrderMapper.selectExportScopeStats`（聚合快照）；聚合谓词在 Service 内私有方法复制列表筛选语义，**不依赖** `GET /api/order`，保证"不沿用列表接口"的约束。
- **错误语义分层**：`ExportBizException` + `GlobalExceptionHandler` 两个新 handler 实现真实 400/409；旧 IAE handler 未动，所以 `GET /api/order` 的 "HTTP 200 + code=400" 行为在 curl 回归里保持不变。
- **前后端常量强一致**：后端 409 文案字面量与 `frontend/src/constants/export.ts` 逐字一致，前端据此把"已有相同导出任务"当作成功、把"幂等值冲突"当作异常。
- **前端调用链同步改**：`request.ts`(post+ApiError) 被 `http/export.ts` 用，后者被页面调用；`ExportModal` 的 `onConfirm` 类型改为 Promise 后，页面处理器与测试桩都必须同步异步化，否则 typecheck/测试失败。
- **实体与枚举类型边界**：`export_mode/status` 在实体存 String、在 DTO/VO 边界转枚举，规避 MyBatis 枚举 TypeHandler 假设，三端（DB 字符串/Java String/枚举）口径统一。
