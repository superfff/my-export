# 数据库状态流参考 - phase 9（消费者真实执行：keyset 批读 + SXSSF 写 excel + processed_rows + SUCCESS/FAILED）— 供后续迭代

> 用途：记录本阶段(9.0)相对 8.0 引入的表结构改动（V4/V5）与"回写状态 / 新建数据"的全部代码路径与事务边界。
> **本文部分作废并取代 8.0 after-doc（`2026-09-04-phase8-db-state-flow.md`）中关于 `outbox_events.status` 的表述**：V4 已删 `status` 列与 `idx_outbox_pending`，8.0 的"`PENDING ⟺ published_at IS NULL`"不变量失效，改为"未发布 ⟺ `published_at IS NULL`"。涉及 outbox 判据处以本文为准。
> 后续迭代（下载/文件对外提供、RUNNING 卡死恢复、失败重试成新 attempt、导出文件命名对外化）先读本文，再动代码。

## 一、本阶段涉及的表（4 张，含 V4/V5 后的列形态）

### 1. `export_jobs`（任务主表 = 任务级权威状态）
V2 建表 + V5 增列后的列：`id / idempotency_key(uk) / request_hash / export_mode / status / filename / export_columns / scope_snapshot / expected_total / max_order_id / processed_rows / created_at / updated_at`。
- `status` 取值 `PENDING / RUNNING / SUCCESS / FAILED`，**全库唯一权威任务状态**；乐观锁用"状态即版本"：`WHERE id=? AND status=?`。
- `processed_rows`（V5，BIGINT UNSIGNED DEFAULT 0）：已成功写入 excel 的行数 = 导出进度。RUNNING 期间每成功写一批自增；SUCCESS 终态 = 实际导出条数；FAILED 保留已提交进度不回填。
- `expected_total` = 创建时命中订单总数（进度分母）；`max_order_id` = 创建时命中最大 `t_order.id`（高水位，导出期间新增行不入文件）。

### 2. `outbox_events`（V3 建表，V4 减负）
记录"一条任务的投递生命周期"，**不存任务状态**。V4 后列：`id / job_id(uk, 1任务=1事件) / trace_id / published_at / attempt_count / created_at / updated_at`。
- **发布与否唯一判据：`published_at IS NULL` = 未发布**（V4 删除了 `status` 列与 `idx_outbox_pending`）。
- `published_at` 仅 broker confirm 且无路由 return 后回填（同一 UPDATE，再次校验 `published_at IS NULL` 防并发轮次重复回写）。
- dispatcher 扫描 = `WHERE published_at IS NULL ORDER BY id LIMIT 100`，走主键序，无专用索引（别为 `published_at IS NULL` 造复合索引）。
- `attempt_count` = 导出执行尝试次数 = 消费者成功领取次数 = 恒等于该任务 `export_job_attempt` 行数。

### 3. `export_job_attempt`（本次执行级状态，V3 建表不变）
列：`id / event_id / job_id / attempt_no(uk: job_id+attempt_no) / status / started_at / finished_at / error_message / created_at`。
- `status` 取值 `RUNNING / SUCCESS / FAILED`；本期 1 任务 = 1 attempt（`attempt_no=1`），两表状态恒同步。
- V5 之后本表新增回填：执行结束时 `finished_at=NOW()`、`error_message`（FAILED 时，VARCHAR(1000) 截断）。

### 4. `t_order`（V1，只读）
`create()` 聚合 `count/max(id)` 与 `executeExport` 的 keyset 批读用到；dispatcher 不碰。

## 二、"回写状态 / 新建数据" 全部路径（改动前先对号入座）

| # | 触发 | 表动作（同一事务 ✓ = 单事务原子） | 所在方法 |
|---|---|---|---|
| 1 | `POST /api/export-job` 首次合法 | ✓ insert `export_jobs(status=PENDING, processed_rows=0)` + insert `outbox_events(published_at=NULL, trace_id=请求traceId)`；job 与 outbox **同生同灭** | `ExportJobServiceImpl.create`（@Transactional） |
| 1b | 同 Key 重发（409） | 不写任何表；duplicate/conflict 语义看 `request_hash` | `respondConflictByKey` |
| 2 | dispatcher 轮询（5s） | 查 `outbox_events WHERE published_at IS NULL`，投递到 `export.exchange/export.job`；**confirm 且无 return** 才 UPDATE `published_at=NOW() WHERE id=? AND published_at IS NULL` | `OutboxDispatcher.dispatch / markPublished` |
| 2b | MQ 不可用 / 未确认 / return | 不改库，行保持 `published_at IS NULL`，下轮自动补投（允许重复投递） | `OutboxDispatcher` catch |
| 3 | 消费者取到消息 | ✓ CAS `UPDATE export_jobs SET status=RUNNING WHERE id=? AND status=PENDING`；影响=1 才继续，否则直接 ack 丢弃 | `ExportJobService.claim`（@Transactional） |
| 3a | 抢占成功同一事务 | UPDATE `outbox_events SET attempt_count=attempt_count+1`；insert `export_job_attempt(status=RUNNING, attempt_no, started_at=NOW())` | `claim` |
| 4a | 每成功写一批（进度推进） | 独立短事务（无外层事务）：`UPDATE export_jobs SET processed_rows=processed_rows+本批行数 WHERE id=?`；游标 `lastId=本批末行 id`，批不满 batch-size 或 `lastId>=max_order_id` 结束 | `ExportJobServiceImpl.writeBatches` |
| 4b | 整份文件写完落盘 | ✓ job/attempt 终态同事务：`UPDATE export_job_attempt SET status=SUCCESS, finished_at=NOW(), error_message=NULL WHERE job_id=? AND status='RUNNING'` + `UPDATE export_jobs SET status=SUCCESS, processed_rows=实际条数 WHERE id=? AND status='RUNNING'`；文件保留在 `export.file-dir/export_<jobId>.xlsx` | `finalizeJob`（TransactionTemplate 包两处 UPDATE） |
| 4c | 任一步骤失败 | ✓ 同上但 status=FAILED：attempt 带 `error_message`（≤1000），job `processed_rows` = 最后一次已提交进度（不回填）；半成品文件 best-effort 删除 | `executeExport` catch → `finalizeJob` |

**ack 与 DB 事务的一致性**：
- `claim` 是独立 `@Transactional`（代理生效，消费者 Bean 跨类调用）→ 返回即 RUNNING 已提交。
- `executeExport` 本身**不开长事务**（进度推进为逐批自动提交的短 UPDATE）；终态 `finalizeJob` 用 `TransactionTemplate` 在**方法内部**包两处 UPDATE（`executeExport` 与 `finalizeJob` 同属一个 Service 实现类，`@Transactional` 自调用不会走代理，故终态事务必须显式用 TransactionTemplate——**勿把 finalizeJob 改回 `@Transactional` 注解自调用**）。
- `basicAck` 移到 executeExport 返回（SUCCESS/FAILED 均已提交）之后；`claim=false` 也 ack。只有 executeExport 连终态都落不了而抛出、或反序列化/claim 重试耗尽才 `basicNack(requeue=false)` → DLX。
- claim 提交后、终态落定前崩溃 → 消息重投 → 新消费者 `claim` 0 行 → ack 丢弃 → job 停在 RUNNING。**本期不自动恢复**（R9），文档化留给后续。

## 三、不变量清单（后续迭代不许破坏）

1. `export_jobs.status` = 任务权威状态；`export_job_attempt.status` = 本次执行状态；`outbox_events` 只承载发布生命周期（V4 后仅 `published_at` 表达发布与否）。三表语义不混用。
2. job 与 outbox 同生同灭（同一事务创建/回滚）；1 任务恒 1 outbox 事件。
3. **未发布 ⟺ `published_at IS NULL`**（8.0"PENDING ⟺ published_at IS NULL"已作废，无 status 列）。`published_at` 只在 confirm 且无 return 后置，回写条件带 `published_at IS NULL` 防并发重复回写。
4. `attempt_count = export_job_attempt` 行数；`attempt_no` 从 outbox `attempt_count+1` 取。
5. 抢占/推进/终态一律用"状态即版本"乐观锁（`UPDATE ... WHERE status=?`），不回退状态、不做全表盲改。
6. 真实导出只读 `scope_snapshot`（导出哪些行）+ `max_order_id`（导到哪为止）冻结范围；keyset `id > lastId AND id <= max_order_id ORDER BY id ASC LIMIT batch-size`。**谓词层不带水位/游标**，保证 create 统计与 execute 批读同源。
7. 进度分母 = `expected_total`（创建时统计），`processed_rows/expected_total` 仅供展示；实际导出数以 `processed_rows`（终态）为准。导出期间新增/更新/删除导致的行集合变化**不考虑**。
8. 列白名单唯一来源 = `OrderExportColumns`（create 校验与 execute 表头/取值共用，防两处漂移）。表头顺序 = `export_columns` 用户勾选顺序；status 文案/createdAt 格式/amount 数值与订单列表所见一致。
9. 单元格文本净化规则：剔除 `<0x20` 及 0xFFFE/0xFFFF → 空格；文本首字符为 `= + - @` 前置单引号防公式/注入；amount 走 numeric 单元格不走净化。

## 四、给后续迭代的接续点（phase 10+ 预计要做，先占位）

- **导出文件对外提供/下载**：本阶段文件只落在 `export.file-dir/export_<jobId>.xlsx`（jobId 命名保证唯一），`export_jobs.filename` 尚未用于对外命名。下载接口 + 归档/清理策略留后续。
- **"卡死 RUNNING"恢复**：claim 已提交、终态未落崩溃留下的 RUNNING 任务，本期只留日志（executeExport 注释），恢复机制（探活/重试成新 attempt）留后续。
- **失败重试成新执行**：第二次抢占出现时 `attempt_no` 取 outbox `attempt_count+1`（已支持 >1），届时 `export_jobs.status` 跟随最近一次 attempt。
- **导出中心"成功"页的完成态指标**：`finished_at / file_size` 仍为前端预留字段，后端尚未返回；将来文件对外化时一并补。
- **死信告警/重放**：`export.job.dlq` 里的消息如需人工补偿，可按消息体 `jobId` 重投；消费侧不会因此重复执行（见不变量 5）。

## 五、踩坑备忘（本阶段实测/约定，供复用）

- **自调用 @Transactional 不生效**：`executeExport` 内部调 `finalizeJob` 属同类自调用，注解事务会静默失效 → 两处 UPDATE 不再原子。**用注入的 `TransactionTemplate` 包住终态两处 UPDATE**（见二表 4b/4c）。`claim`/`create` 由消费者/控制器跨 Bean 调用，`@Transactional` 代理生效，不受影响。
- **枚举常量初始化期前向引用**：`OrderExportColumns` 常量初始化 lambda 内不能直接引用后声明的 static `DateTimeFormatter`（编译报"非法前向引用"），经私有静态方法中转即可。
- **SXSSF 与进度节奏**：SXSSF 行窗口(row-window)与 DB keyset 批大小(batch-size)是两个正交配置（yml `export.sxssf-row-window` / `export.batch-size`）。写批成功才 `processed_rows += 批行数` 并推进 lastId；进度/终态都走独立短事务，绝不用一个长事务包整个导出。
- **manual ack + 容器级重试不能进死信（8.0 沿用）**：容器不替消费者 reject；重试与最终处置都在 `ExportJobConsumer` 方法内（`RetryTemplate` 5 次 + 显式 `basicNack` → DLX）。
- **Spring AMQP 3.1.7 API 差异（8.0 沿用）**：confirm/returns 在连接工厂层（yml `publisher-confirm-type: correlated` / `publisher-returns: true`），`CorrelationData` 无 `isAck()`，用 `getFuture().get()` 读 `Confirm.isAck()`。
- **本地导出目录勿提交**：根 `.gitignore` 已加 `backend/data/`；docker 用命名卷 `export-files:/app/data/export` 持久化。
