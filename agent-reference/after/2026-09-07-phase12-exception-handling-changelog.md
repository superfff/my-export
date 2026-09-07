# 变更日志 - 2026-09-07 13:27

## 概述

按 `agent-reference/before/12.0.prompt.md` 实施「异常处理四件套」：给导出任务补上 **防倒退（job_version 写序号守卫）→ FAILED 重试 → SUCCESS 文件 24h 过期（EXPIRED）→ 孤儿/卡死 RUNNING 的租约回收**。这是"把异常情况兜住"的一轮全栈改造：正常情况下系统会自己处理失败重试、文件过期、worker 崩溃留下僵尸任务这三类问题，不再需要人工干预数据库。

## 本次变更的目标

1. **防倒退**：给 `export_jobs` 加单调写序号 `job_version`，活跃 worker 每次回写进度/终态都带版本 CAS，防止"旧 attempt / 旧 worker"因网络延迟把新结果覆盖掉（堵住数据倒退回写）；同时为将来的 SSE 进度推送预埋"越大越新"的排序字段（本期不实现 SSE）。
2. **FAILED 重试**：只有"导出失败"的任务能点重试；重试 = 复用同一 outbox 行重新入队（≤5s 自动再跑），旧失败记录归档不可改；前端失败行出现"重试"按钮。
3. **EXPIRED 过期**：成功的文件 24 小时后过期，每小时定时扫描"先删文件、再置 EXPIRED"，过期后不能下载也不能重试。
4. **卡死恢复（心跳 + 租约）**：worker 每写一批进度就刷新心跳/租约（默认 5 分钟）；扫描发现 RUNNING 但租约超时/缺失（说明 worker 已挂/卡死）就把该任务置 FAILED 并删掉残留文件，让用户能重试。同时按需把 `.tmp` 中间文件按"本次执行版本号"命名，隔离不同执行，避免新旧 worker 写同一个文件互相踩踏。

## 变更详情(对所有变更都进行罗列)

### backend/src/main/resources/db/migration/V7__add_export_jobs_exception_handling.sql

- **修改类型**：新增
- **修改内容**：给 `export_jobs` 加 4 列 —— `job_version`（单调写序号，默认 0）、`heartbeat_at`（心跳时间）、`lease`（租约截止，=心跳+N 秒）、`expires_at`（成功文件过期时间）；加复合索引 `idx_status_expires(status, expires_at)`；两段存量回填：已 SUCCESS 的行补 `expires_at=finished_at+24h`，已 RUNNING 的行补 5 分钟心跳/租约宽限（避免部署后立刻被回收扫描误杀）。
- **修改原因**：Flyway 版本化迁移，为阶段 12 的四类异常处理落存储支撑列。存量回填是为了"老数据也能被新逻辑管理"（不然老的成功文件永远不会过期、老的 RUNNING 僵尸会部署即被回收）。

### backend/src/main/java/com/example/export/entity/ExportJob.java

- **修改类型**：修改
- **修改内容**：实体补 `jobVersion / heartbeatAt / lease / expiresAt` 四个字段及 getter/setter。
- **修改原因**：MyBatis-Plus 靠实体字段映射表列，不补字段就读不到、也写不进这四列。

### backend/src/main/java/com/example/export/enums/ExportJobStatus.java

- **修改类型**：修改
- **修改内容**：状态机枚举新增 `EXPIRED`（注释标明仅 SUCCESS→EXPIRED 流转）。
- **修改原因**：引入"已过期"终态，列表筛选（后端按枚举校验）与状态流转都自动接纳它。

### backend/src/main/java/com/example/export/dto/ExportJobVO.java

- **修改类型**：修改
- **修改内容**：VO record 在 `processedRows` 后加 `Long jobVersion`。
- **修改原因**：把单调写序号透传给前端（列表/创建响应都带），为将来 SSE 进度推送排序用。

### backend/src/main/java/com/example/export/support/ExportFileStore.java

- **修改类型**：修改
- **修改内容**：新增 `attemptTmpFile(jobId, startSeq)`（= `root/<jobId>/export.<startSeq>.tmp`）与 `deleteAttemptTmp`（只删某次执行的 tmp）。
- **修改原因**：tmp 文件名带上本次执行在 claim 时的版本号 → 不同执行写盘的文件互不相交，防 stale（旧/卡死）worker 与新一次执行写同一个 `.tmp` 造成内容撕裂或互相误删。

### backend/src/main/java/com/example/export/service/impl/ExportJobServiceImpl.java

- **修改类型**：修改
- **修改内容**：
  - `claim` 抢占 UPDATE 扩为：置 RUNNING + `job_version+1` + 重置心跳/租约；
  - `executeExport` 守卫化：捕获 `startSeq`，每批进度 UPDATE 带 `WHERE job_version=<预期> AND status='RUNNING'` CAS 并顺带刷心跳/租约 + bump，0 行即抛内部 `StaleExecutionException`；写完发布前先复查归属，已被接管则不发布；Stale 单独 catch —— 释放资源、只删自己那次 tmp、**静默返回**（不落终态、不进死信）；真失败 catch 保持清整目录 + 落 FAILED；
  - `finalizeJob` 改签名加 `seq`，顺序改为**先 job 门（版本+状态 CAS）成功再改 attempt**，job 门 0 行 → 判定已易主则归 Stale；SUCCESS 时同一条 UPDATE 写 `expires_at`（用 DB 侧 `DATE_ADD(NOW(),…)` 避免时区漂移）；
  - `writeBatches` 版本 CAS 改写 + 心跳便车 + 写序号同步；
  - 新增 `retry(jobId)`：同事务 FAILED→PENDING + 进度归零 + `job_version+1` + 同一 outbox 行 `published_at→NULL` 交由 dispatcher 重投；
  - `downloadSource` 在"非 SUCCESS 一律 409"前加 EXPIRED→404"已过期清理"分支；`toVO` 补 jobVersion；列表状态错误文案加 EXPIRED。
- **修改原因**：核心逻辑全部在这——守卫矩阵防倒退回写、Stale 静默退出防止被接管后仍去污染新执行、重试复用 outbox、过期文件 404、心跳/租约随写批刷新零额外 DB 写。

### backend/src/main/java/com/example/export/service/ExportJobService.java

- **修改类型**：修改
- **修改内容**：接口新增 `ExportJobVO retry(long jobId)` 及契约注释。
- **修改原因**：service 接口需跟上新增的重试能力，供 controller 调用。

### backend/src/main/java/com/example/export/controller/ExportJobController.java

- **修改类型**：修改
- **修改内容**：新增 `POST /{id}/retry` 端点。
- **修改原因**：对外暴露重试入口；语义（404/409、200 返回新 VO）由 service 的 BizException 保证，controller 只转发。

### backend/src/main/java/com/example/export/recovery/ExportRecoveryScanner.java

- **修改类型**：新增
- **修改内容**：新组件两个 `@Scheduled` 扫描任务：① 租约回收（60s）扫 `RUNNING AND (lease IS NULL OR lease<NOW())`，每行同一事务把 job + 当前 RUNNING attempt 都置 FAILED（attempt 带"执行租约过期"原因），事务落定后删任务目录；② 过期回收（1h）扫 `SUCCESS AND expires_at<=NOW()`，**先删文件再置 EXPIRED**（status+expires_at 双 CAS）。内部批量循环各配"满批零处置即停"的防空转守卫。
- **修改原因**：异常恢复不能靠人，用定时扫描自动收口。只置 FAILED 不自动重投，新执行仍由用户点重试，避免死循环。

### backend/src/main/resources/application.yml

- **修改类型**：修改
- **修改内容**：加 `spring.task.scheduling.pool.size: 4`、`export.lease-seconds: 300`、`export.expire-hours: 24`。
- **修改原因**：`@Scheduled` 任务从 1 个变 3 个，默认单线程调度器会被慢扫描拖住 dispatcher，故开调度线程池；租约/过期时长做成可配置。

### backend/src/test/java/com/example/export/support/ExportFileStoreTest.java

- **修改类型**：修改
- **修改内容**：新增用例 `layout_attemptTmpScopedBySeq`，断言不同 startSeq 的 tmp 文件名互不相同、最终文件名为唯一 `export.xlsx`。
- **修改原因**：用纯路径单测锁死 R5"跨执行 tmp 隔离"这条最容易回归的约定。

### frontend/src/types/order.ts

- **修改类型**：修改
- **修改内容**：`ExportJobStatus` 加 `'EXPIRED'`；`ExportJobVO` 加 `jobVersion: number`。
- **修改原因**：前端类型需与后端状态机/VO 对齐，否则编译期无法表达"已过期"与新字段。

### frontend/src/constants/export.ts

- **修改类型**：修改
- **修改内容**：`EXPORT_JOB_STATUS` 增 `EXPIRED: { text:'已过期', color:'default' }`；**不加 EXPIRED tab**。
- **修改原因**：让 EXPIRED 行在列表状态列显示"已过期"；tab 按源需求不加，全部列表可见即可。

### frontend/src/http/export.ts

- **修改类型**：修改
- **修改内容**：新增 `retryExportJob(id)` = `post('/api/export-job/{id}/retry')`。
- **修改原因**：封装重试请求；错误走统一 ApiError，toast 展示后端 409 原文。

### frontend/src/pages/export-center/index.tsx

- **修改类型**：修改
- **修改内容**：`columns`（列定义）与 `handleDownload` 从模块级迁入组件（`useMemo`/`useCallback`）；操作列按状态渲染：SUCCESS→"下载文件"、FAILED→"重试"、其余（含 EXPIRED）为空；新增 `handleRetry`（成功后调 `load(false)` 刷新列表，失败 toast 后端文案）；轮询谓词扩到 PENDING。
- **修改原因**："重试"成功后必须触发本组件 `load` 刷新列表（FAILED 行消失/转 PENDING），模块级函数够不着组件作用域，故必须把列/动作迁进组件。轮询扩到 PENDING 是因为重试后任务会短暂 PENDING→RUNNING，只盯 RUNNING 会漏掉这段、RUNNING 到了也没人刷新。

### agent-reference/after/2026-09-07-phase12-exception-handling.md

- **修改类型**：新增
- **修改内容**：phase 12 实施后的权威 after-doc（job_version 语义与守卫矩阵、Stale 静默退出约定、重试/EXPIRED/心跳租约契约、V7 结构、前端 R-F 口径、门禁结果、给 phase13 的接续点）。
- **修改原因**：按仓库惯例，每个阶段完成后沉淀权威参考，供后续迭代先读再动，避免破坏不变量。

## 关联说明

- **同一条写序号贯穿两端**：后端 `job_version` 在 claim/每批进度/finalize/重试/双回收里单调 +1，既是活跃 worker 的乐观锁，也是 `ExportJobVO`/前端 `jobVersion` 透传的快照序号。前端本期只透传、不做按版本拦截（因为轮询是整表快照替换，"等版本⇒同内容"）；"只接受更大版本"已写入 after-doc 作为 phase13 SSE 合并契约。
- **tmp 隔离与 Stale 配套**：`executeExport` 用 `attemptTmpFile(startSeq)` 写盘 + 发布前归属复核；Stale 时只 `deleteAttemptTmp` 自己那次（不 `deleteTaskDir`），避免删掉接管者的新文件。真失败仍整目录清理，配合租约回收兜底"已发布未回写"的孤儿 `.xlsx`。
- **重试链路三处联动**：`retry`（service）把 FAILED→PENDING + outbox 置未发布 → dispatcher ≤5s 重投 → `claim` 开新 attempt（attempt_no+1）；前端操作列 FAILED 行"重试"→ `retryExportJob` → 组件内 `load` 刷新，轮询已扩 PENDING 保证能刷到 RUNNING/终态。旧 FAILED attempt 永不 UPDATE = 归档。
- **双回收与下载/前端语义联动**：EXPIRED 由过期回收置入（先删文件），因此 `downloadSource` 对 EXPIRED 走 404（文件缺失同族）而非 409；前端 EXPIRED 行只展示"已过期"、无下载/重试按钮。
- **调度线程池**：加了 3 个 `@Scheduled`，必须同步配 `spring.task.scheduling.pool.size`，否则默认单线程调度器会被 60s/1h 扫描拖住 5s 的 outbox dispatcher。
