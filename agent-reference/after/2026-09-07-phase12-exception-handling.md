# phase 12.0 after-doc：异常处理四件套（防倒退 job_version / FAILED 重试 / EXPIRED 过期 / 心跳租约回收）— 供后续迭代

> 依据 `agent-reference/before/12.0.prompt.md` 实施（实施裁决以该 prompt 第二节为准）。在 11.0（下载对外）产物之上
> **新增**异常处理，**不改**下载接口 200/409/404 契约主体、outbox 投递判据（`published_at IS NULL`）、claim/ack/死信语义、
> `resolve` 越界守卫、`.tmp`→原子发布、四表职责。DB 状态流仍以 `2026-09-04-phase9-db-state-flow.md` 为准
> （卡死 RUNNING 恢复接续点已由本阶段心跳租约对口）；文件流转以 `2026-09-05-phase10-file-flow-hardening.md` 为准；
> 下载契约以 `2026-09-06-phase11-download.md` 为准。

## 一、本阶段新增/改动的表结构（V7）

`V7__add_export_jobs_exception_handling.sql`：`export_jobs` 追加四列 + 复合索引 + 两段存量回填：

- `job_version BIGINT NOT NULL DEFAULT 0`：**单调写序号，本 job 全生命周期只增不减**。凡把 `export_jobs` 往前推进的
  状态/进度写入都 `job_version = job_version + 1`：claim(RUNNING)、每批进度、finalize 终态(SUCCESS/FAILED)、
  重试(PENDING)、租约回收(FAILED)、过期回收(EXPIRED)。双重角色：(a) 活跃 worker 写回的乐观 CAS 依据
  （`WHERE id=? AND job_version=<预期> AND status='RUNNING'`，0 行=已易主）；(b) 读者/将来 SSE 的快照序号（更大版本=更新）。
  attempt 表**不加** job_version（每行一次执行、终态只读，已有 `attempt_no` 表达执行序号）。存量 job_version 均 0（DEFAULT），新 claim 即单调递增。
- `heartbeat_at` / `lease`（两列都写 jobs，冗余可读）：心跳=最近一次证明存活的批次/进度写入时间；租约=该心跳+租约秒数，
  随每次写批**同一 UPDATE 顺带刷新**（心跳零额外 DB 写）。claim 命中时重置（新执行拿全新租约）。
- `expires_at`：成功文件过期时间，SUCCESS 落终态时以 DB 侧 `DATE_ADD(NOW(), INTERVAL ? HOUR)` 写入（与过期扫描比较的
  `NOW()` 同源，避免应用/DB 时区漂移）；存量 SUCCESS 回填 `finished_at + 24h`。
- `idx_status_expires(status, expires_at)`：过期回收谓词走它。
- 不建 `idx(lease)` / `idx(job_version)`：租约/过期扫描都从 status 收窄；job_version 按主键 `WHERE id=?` 定位无需索引。

## 二、job_version 守卫矩阵（本次最关键落点）

活跃 worker（execute 内）在开始时读 `startSeq = job.jobVersion`（claim 已提交 → 该次执行 claim 后的版本号），
维护本地预期号 `seq`（初值 = startSeq，每成功写回一次 +1，与 DB 同拍）：

- **每批进度 UPDATE**：`SET processed_rows=processed_rows+批数, heartbeat_at=NOW(), lease=DATE_ADD(NOW(),INTERVAL n SECOND),
  job_version=job_version+1 WHERE id=? AND job_version=<seq> AND status='RUNNING'`；0 行 → 抛 `StaleExecutionException`。
- **finalizeJob 先 job 门后 attempt**：job 门 `SET status=终态, ..., job_version=job_version+1 WHERE id=? AND job_version=<seq>
  AND status='RUNNING'` 成功（1 行）后才 UPDATE RUNNING attempt；job 门 0 行 → selectById 判定：job 非 RUNNING 或版本已推进
  → 归 Stale（`StaleExecutionException`），否则 IllegalStateException。
- **claim/retry/租约回收/过期回收**（非活跃 worker 的状态写入）用 `status` CAS 且同样 `job_version+1`，不做版本 CAS
  （它们本就无权在该行 RUNNING 时写）。

**Stale 静默退出约定**：`StaleExecutionException`（私有静态内部类）只在本类出现；`executeExport` 单独 `catch(Stale)` 在
`catch(Throwable)` 之前 → dispose + 只删自己 `startSeq` 的 tmp + log.warn + **正常返回**（不落终态、不 `deleteTaskDir`、
不进 DLQ；消费者照常 ack —— job 已归他人是既定事实）。真失败（本执行仍归属、DB 可达）保持 catch(Throwable)：dispose +
`deleteTaskDir` → 尝试 `finalizeJob(FAILED)`；若落 FAILED 途中发现已易主（finalize 抛 Stale）→ 包一层吞掉，不误进 DLQ；
连终态都落不下（DB 挂）→ 抛出 → 消费者 nack → DLX（原语义不变）。

## 三、执行级写盘隔离（R5）

`ExportFileStore.attemptTmpFile(jobId, startSeq)` = `root/<jobId>/export.<startSeq>.tmp`：claim 每次 +1 → 跨执行唯一，
每个 worker 只写自己 startSeq 的 tmp，Stale 退出只 `deleteAttemptTmp` 自己那次 —— 不同执行写盘互不相交。
`finalFile` 仍唯一 `export.xlsx`，只由 `publishAtomically(自己的 tmp → export.xlsx)` 原子 move 覆盖产出。
**发布前归属复核**：写完 close() 后 selectById 复核 `RUNNING 且 jobVersion==seq` 才发布。
**残余窗口（接受并文档化）**：版本复核与 move 之间的微秒级竞争，stale 内容可能覆盖新文件，但 DB 永不登记错 file_path/file_size，
且 SUCCESS 的 file_size 以磁盘实读为准。**已知合理顾虑（升级点）**：SXSSF `close()`（把整本流式写出到 tmp）期间不再心跳，
超大导出若 close 超过租约时长会被误回收 —— 每批心跳是便车式设计，命中后再上独立心跳线程/关 close 前补一次心跳。

## 四、FAILED 重试（R1）

- 接口：`POST /api/export-job/{id}/retry`（无 body），仅 FAILED。
- service 同事务：job `FAILED→PENDING` + `processed_rows=0`（重跑从 0 计，旧进度不并入）+ `job_version+1`；同一 outbox 行
  `published_at→NULL`（1 任务=1 事件，只能复用这行）；随后既有 5s dispatcher 重投 → claim CAS `PENDING→RUNNING` 再 `+1` →
  开新 attempt（`attempt_no = attempt_count+1`）。旧 FAILED attempt 行原样归档，**任何代码不 UPDATE 已终态 attempt**。
- 语义：job 不存在 404；非 FAILED（PENDING/RUNNING/SUCCESS/EXPIRED）→ 409"仅失败状态的任务可重试"；并发双击第二次命中
  job CAS 0 行 → 同 409（天然幂等）。成功 200 + 新 VO（status=PENDING, processedRows=0, jobVersion 已 +1）。
- 与下载一样：状态异常一律 `BizException` 真实 HTTP + envelope，绝不抛裸 IllegalArgumentException。

## 五、EXPIRED / expires_at（R2）

- 仅 SUCCESS → EXPIRED（过期回收扫描置入）。EXPIRED 不可下载/重试。
- SUCCESS 落终态（finalize 同一 UPDATE）写 `expires_at`（DB 侧 `NOW()+expire-hours`，默认 24h）。
- 过期扫描（约 1h）：`SUCCESS AND expires_at IS NOT NULL AND expires_at <= NOW()` 分批 LIMIT 50 → **先 `deleteTaskDir` 再置 EXPIRED**
  （置状态走 `status + expires_at` 双 CAS 并 `job_version+1`；0 行=已处理跳过）。attempt 表不落 EXPIRED（SUCCESS attempt 是执行历史，保留）。
- 下载：`downloadSource` 在"非 SUCCESS 一律 409"前加 EXPIRED 分支 → `BizException(404, "导出文件已过期清理…")`（文件已删，走 404 语义）。
- 24h 边界内、扫描未跑的空窗（分钟级）仍 SUCCESS 可下载 —— 接受，扫描是唯一回收口，download 不做 expires 预判。
- 前端：状态列文案"已过期"（color default）；**不加 tab**；EXPIRED 行无按钮。

## 六、心跳 / 租约回收（R3/R7）

- claim 命中重置 heartbeat=NOW(), lease=NOW()+lease-seconds（默认 300s）；每批进度 UPDATE 顺带刷新两列。
- 租约回收 `ExportRecoveryScanner`（`export/recovery/`，@Component，与 dispatcher 职责分开）：`@Scheduled(fixedDelay=60s, initialDelay=30s)`
  扫 `RUNNING AND (lease IS NULL OR lease < NOW())` → 每行同一 TransactionTemplate：RUNNING attempt 置 FAILED（error_message="执行租约过期(心跳停止),由扫描回收"）
  + job 置 FAILED（status CAS，0 行抛 → 整事务回滚，防与真实终态/并发竞争）→ 事务落定后 `deleteTaskDir`（清遗留 .tmp 与"已发布未回写"的 .xlsx）。
  **只置 FAILED、不重投 outbox**，新执行由人点"重试"触发。
- 过期回收 `@Scheduled(fixedDelay=1h, initialDelay=60s)` 同组件第二方法（见第五节）。
- `application.yml`：`spring.task.scheduling.pool.size: 4`（现 3 个 @Scheduled：dispatcher 5s / 租约 60s / 过期 1h，默认单线程调度器会被拖慢）；
  `export.lease-seconds: 300`、`export.expire-hours: 24`。
- `lease IS NULL` 分支覆盖 V7 迁移时"已 RUNNING 且无心跳/从未心跳"的残留行（迁移已回填一次 5 分钟宽限）。

## 七、孤儿清扫（R6，不新增扫描）

12.0"已完成的场景"由既有三点覆盖，未新增独立扫描：① `.tmp 原子生成失败` → executeExport catch(Throwable) 的 `deleteTaskDir` 与
Stale 分支删己 tmp；② `未登记 .xlsx`（=已发布但 finalize 未落 SUCCESS、job 停 RUNNING、file_path NULL）→ 租约回收把 job 置 FAILED 后 `deleteTaskDir` 整目录删；
③ 真孤儿目录（job 行不存在）不存在删除路径，不做兜底。

## 八、前端（R8 + R-F）

- 类型 `types/order.ts`：`ExportJobStatus` 加 `EXPIRED`；`ExportJobVO` 加 `jobVersion: number`（processedRows 后）。
- 常量：`EXPORT_JOB_STATUS` 加 `EXPIRED: { text:'已过期', color:'default' }`；**不加 tab**（后端 list 校验已含 EXPIRED，全部 tab 可见即可）。
- 导出中心 `export-center/index.tsx`：**columns 迁入组件（useMemo，deps=[handleDownload, handleRetry]）**，动作处理器随迁（重试成功须触发组件内 `load` 刷新）。
  操作列按状态渲染：SUCCESS→"下载文件"、FAILED→"重试"、其余（PENDING/RUNNING/EXPIRED）→ 空；列宽放宽到 130。
  轮询谓词扩到 PENDING（R8）：重试提交后 job 会 PENDING(≤5s)→RUNNING，只看 RUNNING 会漏 tick。移除了 hasRunning 冗余 state。
- `http/export.ts`：`retryExportJob(id)` = `post('/api/export-job/{id}/retry')`；失败统一 `ApiError`，handleRetry toast 后端 409 原文。
- **jobVersion 本期只透传、轮询不做版本 gate（R-F）**：现轮询是整表快照替换（`load` 每次 `setList(result.list)` + 请求取消，无乱序），
  "版本相等⇒内容相同"（同版本无写入），强行"只接受更大"会把静止的等版本行丢弃造成闪烁。**"只接受严格更大的版本号"作为 phase13 SSE
  增量事件合并契约落档**（事件乱序/重放时按版本去旧），本期不实现 SSE、不做按版本拦截。

## 九、测试与门禁（本次实测）

- 后端 `./mvnw compile` + `./mvnw test` 全绿：含 ExportFileStoreTest（新增 `layout_attemptTmpScopedBySeq`：attempt tmp 按 startSeq 命名互不相交、final 名恒唯一）、OrderExportColumnsTest。
- 前端 `pnpm typecheck` 通过；`pnpm test`（vitest）29 全绿。
- **未做 docker 全栈 e2e**（环境无运行中的 stack）。`before/12.0.prompt.md` 第十二节验收清单的 DB 层面手工断言
  （V7 列/回填、job_version 单调推进、重试 200/409/404 与旧 attempt 归档、租约回收 60s 生效、过期回收先删文件再 EXPIRED + 下载 404 + retry 409、
  Stale 手动按旧版本 UPDATE 影响 0 行）留待起栈后逐条打。

## 十、给 phase 13 的接续点

- **SSE 进度推送**：job_version 已落库落 VO；按"只接受严格更大版本"合并增量事件；轮询谓词已含 PENDING/RUNNING。
- **下载审计 / 大文件断点续传 Range / 批量下载 zip**：见 11.0 after-doc 第六节，仍待做。
- **DLQ 自动重放**：本阶段只置 FAILED、不自动重投；DLQ 消息可按消息体 jobId 人工重投（消费侧幂等由抢占兜底）。
- **前端 EXPIRED tab / 过期策略可见化**：源摘要未要求，本期不加。
- **超大导出 close() 心跳缺口**：见第三节升级点。
