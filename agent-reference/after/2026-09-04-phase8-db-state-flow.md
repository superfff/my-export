# 数据库状态流参考 - phase 8（outbox + RabbitMQ + 消费领取）— 供后续迭代

> 用途：记录本阶段(8.0)引入/改动的数据库表，以及所有"回写状态 / 新建数据"的代码路径与事务边界。
> 后续迭代（真实文件导出、SUCCESS/FAILED、失败重试成新 attempt、导出中心状态列等）先读本文，再动代码，避免两处口径漂移。

## 一、本阶段涉及的表（4 张）

### 1. `export_jobs`（阶段 6 引入，本阶段新增"被消费方回写 RUNNING"路径）
任务主表 = **任务级权威状态**。V2 建表列：`id / idempotency_key(uk) / request_hash / export_mode / status / filename / export_columns / scope_snapshot / expected_total / max_order_id / created_at / updated_at`。
- `status` 取值 `PENDING / RUNNING / SUCCESS / FAILED`（`ExportJobStatus`），**全库唯一权威任务状态**；
- 无独立 `version` 列，乐观锁用"状态即版本"：`WHERE id=? AND status=?`。

### 2. `outbox_events`（本阶段 V3 新建）
记录"一条任务的投递生命周期"，**不存任务状态**。列：

| 列 | 含义 / 不变量 |
|---|---|
| `id` | 事件 id（消息体 `eventId`、`x-trace-id` 落账锚点） |
| `job_id` `uk_outbox_job_id` | 关联 `export_jobs.id`，**1 任务 = 1 事件**（唯一） |
| `trace_id` | 创建请求 traceId（历史 backfill 为 NULL，投递时兜底 UUID） |
| `status` | `PENDING / PUBLISHED`，**`PENDING ⟺ published_at IS NULL`** |
| `published_at` | 仅 broker confirm 且无路由 return 后回填 |
| `attempt_count` | 导出执行尝试次数 = 消费者成功领取次数 = 恒等于该任务 `export_job_attempt` 行数 |
| `created_at / updated_at` | — |
索引：`idx_outbox_pending(status,id)` 供 dispatcher 取最早一批。

### 3. `export_job_attempt`（本阶段 V3 新建）
导出动作表 = **本次执行级状态**（一次"领取执行"一行）。列：`id / event_id(→outbox_events.id) / job_id / attempt_no(uk: job_id+attempt_no) / status / started_at / finished_at / error_message / created_at`。
- `status` 取值 `RUNNING / SUCCESS / FAILED`（本期恒 `RUNNING`，无 PENDING）；
- **前瞻语义（已拍板）**：将来一次任务失败可重试成"新一次执行"时，一行 = 一次执行；`export_jobs.status` 取最近一次执行的结果。本期 1 任务 = 1 attempt（`attempt_no=1`），两表状态恒同步。

### 4. `t_order`（阶段 1 引入，本阶段只读）
`create()` 里按 scope 聚合 `count/max(id)` 用，dispatcher/消费者**不碰**。

## 二、"回写状态 / 新建数据" 全部路径（改动前先对号入座）

| # | 触发 | 表动作（同一事务 ✓ = 单事务原子） | 所在方法 |
|---|---|---|---|
| 1 | `POST /api/export-job` 首次合法 | ✓ insert `export_jobs(status=PENDING)` + insert `outbox_events(status=PENDING, trace_id=请求traceId)`；job 与 outbox **同生同灭**，后者失败前者整体回滚 | `ExportJobServiceImpl.create` |
| 1b | 同 Key 重发（409） | 不写任何表（409 分支抛异常回滚，outbox 无残行）。duplicate/conflict 语义看 `request_hash` 与库内一致否 | `respondConflictByKey` |
| 2 | dispatcher 轮询（5s） | 查 `outbox_events WHERE status=PENDING`，投递到 `export.exchange/export.job`（消息持久化 + `x-trace-id` 头）；**收到 confirm 且无 return** 才 UPDATE `status=PENDING→PUBLISHED` + `published_at=NOW()`（同一 UPDATE，再次校验 status=PENDING 防并发重写） | `OutboxDispatcher.dispatch / markPublished` |
| 2b | MQ 不可用 / 未确认 / 路由 return | 不改库，行保持 PENDING，下轮自动补投（**允许重复投递**） | `OutboxDispatcher` catch |
| 3 | 消费者取到消息 | CAS：`UPDATE export_jobs SET status=RUNNING WHERE id=? AND status=PENDING`；影响行数=1 才继续，否则直接 ack 丢弃（重复投递/已非 PENDING 的幂等兜底） | `ExportJobServiceImpl.claimAndRun` |
| 3a | 抢占成功后**同一事务** | UPDATE `outbox_events SET attempt_count=attempt_count+1 WHERE id=?`；取 `attempt_no=加1后值`；insert `export_job_attempt(status=RUNNING, attempt_no, started_at=NOW())`。**任务级(RUNNING) + 本次执行级(RUNNING) 同时落定，任一步失败整体回滚**，不会出现"任务已 RUNNING 却无执行记录" | `claimAndRun` |
| 3b | 消费者对消息处置 | 正常（含抢占失败幂等丢弃）→ `basicAck`；业务异常 → 方法内 `RetryTemplate(5次, 1s×2)` 重试；耗尽 → `basicNack(requeue=false)` → 主队列 DLX → `export.job.dlq`（不改任何 DB 状态） | `ExportJobConsumer` |

**ack 与 DB 事务的一致性**：`claimAndRun` 是独立 `@Transactional` Bean 方法，返回即 DB 已提交，随后才 ack。若提交后、ack 前崩溃 → 消息重复投递 → 重投时 CAS 影响行数=0 → ack 丢弃 → 达成"**允许重复投递，不允许重复执行**"（靠 CAS + `uk_job_attempt(job_id,attempt_no)`，非消息级去重）。

## 三、不变量清单（后续迭代不许破坏）

1. `export_jobs.status` = 任务权威状态；`outbox_events.status` 只管发布生命周期；`export_job_attempt.status` 只管某次执行。三张表语义不混用。
2. job 与 outbox 同生同灭（同一事务创建/回滚）；1 任务恒 1 outbox 事件。
3. `attempt_count = export_job_attempt` 行数；`attempt_no` 从 outbox `attempt_count+1` 取。
4. `PENDING ⟺ published_at IS NULL`；PUBLISHED 只在 confirm 且无 return 后置。
5. 抢占/推进一律用"状态即版本"乐观锁（`UPDATE ... WHERE status=?`），不回退状态、不做全表盲改。
6. 回写 SUCCESS/FAILED 前，先想清楚"任务级 & 本次执行级 & 发布表"三处各自该写哪个字段，别把执行级终态写到 outbox。

## 四、给后续迭代的接续点（phase 9+ 预计要做，先占位）

- **真实文件导出开始/结束**：`claimAndRun` 抢占成功后，执行真实导出 → 结束时 UPDATE `export_job_attempt SET status=SUCCESS/FAILED, finished_at=NOW(), error_message=?`，并把最近一次执行结果同步到 `export_jobs.status`（两处同一事务，权威口径以 attempt 为准）。
- **失败重试成新执行**：第二次抢占出现时 `attempt_no` 取 outbox `attempt_count+1`（已支持 >1），届时 `export_jobs.status` 跟随最近一次 attempt。
- **导出中心状态列 / 进度**：列表读 `export_jobs.status`（RUNNING 列已能查到本阶段置 RUNNING 的任务）；进度分母用 `expected_total`、水位用 `max_order_id`（按 `t_order.id<=max_order_id` 防漂移，阶段 6 已存）。
- **死信告警/重放**：`export.job.dlq` 里的消息如需人工补偿，可按消息体 `jobId` 重投；消费侧不会因此重复执行（见不变量 6）。

## 五、踩坑备忘（本阶段实测，供复用）

- **manual ack + 容器级重试不能进死信**：`acknowledge-mode: manual` 下容器不替消费者 reject，`retry.enabled+max-attempts+default-requeue-rejected=false` 耗尽后 `ConditionalRejectingErrorHandler` 只记日志，消息悬在 unacked 永不进 DLQ。**正解**：消费者方法内 `RetryTemplate(maxAttempts=5, 指数退避)` + 耗尽显式 `basicNack(deliveryTag,false,false)` → DLX。代码与 8.0.prompt.md 均已按此落地。
- **Spring AMQP 3.1.7 API 差异**：`RabbitTemplate` 上**没有** `setPublisherConfirmType/setPublisherReturns`（confirm/returns 在连接工厂层，由 yml `publisher-confirm-type: correlated` / `publisher-returns: true` 开启）；`CorrelationData` 没有 `isAck()`，用 `cd.getFuture().get()` 读 `Confirm.isAck()`。别照网上旧代码抄。
