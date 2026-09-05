# phase 10.0 after-doc：导出文件全流程加固（DB 状态流 + 文件流转变更）— 供后续迭代

> 依据 `agent-reference/before/10.0.prompt.md` 实施。在 9.0（消费者真实执行）产物之上做四类加固：
> 金额/时间单元格文本格式优化、白名单执行期兜底（锁定）、受控路径（root 配置化 + 每任务独立目录 + 越界守卫）、
> `.tmp` → NIO 原子发布 `.xlsx` 的回写流转。**仍不提供下载/对外文件操作。**
> **本文作废并取代 phase9 文档中的旧表述**（见"对 phase9 的作废标注"）：凡提到文件落 `export.file-dir/export_<jobId>.xlsx`
> （flat、jobId 命名）、`amount` 走 numeric 单元格之处，均以本文为准。DB 状态流不变部分（claim→execute→finalize 时序、
> ack 语义、四表职责）仍以 `2026-09-04-phase9-db-state-flow.md` 为准。

## 一、表结构（V6 后 export_jobs 列形态）

V6 `V6__add_export_jobs_file_meta.sql`：`export_jobs` 追加三列（放在 `processed_rows` 之后、`created_at` 之前）：

- `finished_at DATETIME NULL`：任务完成时间。**终态(SUCCESS/FAILED)回填**，与 `export_job_attempt.finished_at` 用**同一 `now`** 双写同值。导出中心【完成时间】列（前端绑 `job.finishedAt`）读此值；终态前为 NULL。
- `file_path VARCHAR(255) NULL`：成功导出文件**相对 root** 路径，形如 `<jobId>/export.xlsx`。仅 SUCCESS 有值。
- `file_size BIGINT UNSIGNED NULL`：文件字节数。仅 SUCCESS 有值。
- 不建索引（只随行读写，无查询谓词）；`updated_at` 由 `ON UPDATE CURRENT_TIMESTAMP` 自动刷新。
- 存量历史行不回填（9.0 终态只在 attempt 上有 finished_at，job 级无从补）。

## 二、文件布局 + 受控路径（`ExportFileStore`）

**布局**：flat `root/export_<jobId>.xlsx` → **每任务独立子目录** `root/<jobId>/`：

```
root = export.file-dir（配置单源，本地 ./data/export / 容器 EXPORT_FILE_DIR=/app/data/export + 命名卷）
  └─ <jobId>/export.xlsx.tmp   ← SXSSF 写入的中间态（写盘对象）
  └─ <jobId>/export.xlsx       ← 对外完整产物（.tmp 同目录原子改名而来）
```

- 新增 `export/support/ExportFileStore.java`（纯路径职责）：构造时 root `toAbsolutePath().normalize()` 一次；
  `taskDir/tmpFile/finalFile/relativePath` 产出每任务路径；**`resolve(相对路径)`** = `resolve→normalize→startsWith(root)`，
  越出 root 抛 `IllegalArgumentException` —— 写入/清理/(将来)下载统一经它；`deleteTaskDir(jobId)` 递归 best-effort 清目录。
- 威胁面：路径唯一动态段是 `jobId`（DB 自增 Long，不经用户输入）；`resolve` 真正用途是**将来下载**消费 DB 存回的
  `file_path` 前做守卫。本期不实现下载，只把守卫函数落地 + 单测 `ExportFileStoreTest`（纯路径断言：`..` 未逃 root 放行、
  `../x` `/etc/passwd` `a/../../x` 越界抛、tmp/final 同目录、jobId 目录隔离）。

## 三、.tmp → 原子发布 流转（executeExport 三分支）

`executeExport(jobId)` 现走：`writer=ExcelFileWriter(store.tmpFile(jobId))` → `open()`（自动建 `root/<jobId>/`）→
writeHeader → writeBatches（keyset + 每批短事务 processed_rows）→ `writer.close()`（flush 使 `.tmp` 完整落盘，此刻 `.xlsx` 尚不存在）
→ `publishAtomically(tmp,out)` → `Files.size(out)` → `finalizeJob(SUCCESS, ..., store.relativePath(jobId), size)`。

`publishAtomically` = `Files.move(tmp,out,ATOMIC_MOVE)`，`AtomicMoveNotSupportedException` 回退普通 `move(REPLACE_EXISTING)`
（同目录同卷 rename 仍原子）。**写 .tmp 的目的**：`.xlsx` 只以完整形态一次性出现，杜绝将来下载/清扫者读到半成品。

**三个失败分支收敛进 executeExport 既有 catch**（唯一收尾路径，散落三套逻辑）：
1. 发布成功 + 回写成功 → SUCCESS；`.tmp` 被 move 消费，无残留。
2. 发布前/发布中失败 → catch 清 taskDir（删半成品 `.tmp`）→ `finalizeJob(FAILED, file 两列=null)`。
3. 发布成功但 `finalizeJob(SUCCESS)` 抛（DB 不可用）→ 落入同 catch：清 taskDir（**已发布的 `.xlsx` 也删**，符合"成功但未回写=不留孤儿文件"）
   → 尝试 `finalizeJob(FAILED)`；FAILED 也落不下 → 抛出 → 消费者 `basicNack` → DLQ（job 可能留 RUNNING，9.0 已文档化的"卡死 RUNNING"边界，不自动恢复）。

## 四、终态回写字段落点（finalizeJob 扩展）

`finalizeJob(jobId, status, processed, errorMessage, filePath, fileSize)`，同一 `TransactionTemplate` 包两处 UPDATE：

- attempt：status、`finished_at=now`、error_message(仅 FAILED)。
- job：status、processed_rows、**`finished_at=now`**（同一 now）；**`filePath/fileSize` 非 null 时才 set**（仅 SUCCESS 带值；FAILED 不触碰 file 两列，新任务恒 NULL）。
- job 与 attempt 的 `finished_at` **同一 now 双写**，保证导出中心【完成时间】与 attempt 完成时刻不漂移。
- `ExportJobVO` 增 `LocalDateTime finishedAt`（record 末位），`toVO` 映射 `job.getFinishedAt()` → 前端既有 `dataIndex:'finishedAt'` 自动点亮，前端零改动。
- `file_path/file_size` 不进 VO（无 UI 消费方，YAGNI，等真下载阶段再接）。

## 五、单元格格式 / 白名单

- **amount → STRING 单元格**：`OrderExportColumns.amount` 返回 `formatAmount(o.getAmount())` =
  `(null→ZERO).setScale(2, HALF_UP).toPlainString()`（`12→"12.00"`、`12.5→"12.50"`、`null→"0.00"`、防科学计数）。
  对 9.0"amount numeric 可求和"的**有意反转**（需求方确认，接受该列不可 SUM / 绿三角 / 左对齐）；不走 `sanitize`（防负值多加前导 `'`）。
  `ExcelFileWriter` 的 Number 分支保留为将来数值列用（本期无列走它）。
- **createdAt** 保持 9.0 `yyyy-MM-dd HH:mm:ss` 文本零改动（R2，补了单测）。
- **白名单执行期兜底**：`readColumns` 遇白名单外字段（`byKey==null`）抛 `IllegalStateException("export_columns 含白名单外字段: "+key)`
  → catch → FAILED（9.0 已实现，**主逻辑未改**）；"特殊渠道"=绕过 `POST /api/export-job` 直插库的任务，靠直插 SQL 的 e2e 锁死；
  create 对白名单外字段仍 400 不变。白名单仍是 `OrderExportColumns` 枚举单源，不引数据库表。

## 六、配置 / 部署 / 不变量

- 无新增配置键；`export.batch-size / sxssf-row-window / file-dir` 原样。目录布局变化只在 root 内部，卷挂载路径不受影响。
- SXSSF 自身行窗口外的旧数据由 SXSSF **spool 到 JVM 临时目录**、`write` 时按序流式统一写出（`compressTempFiles=true` 已压缩），
  实现不手动"刷"窗口外数据 —— 与本期 `.tmp`→原子改名无冲突。
- 不变量保持：job 权威状态 / outbox 未发布 ⟺ `published_at IS NULL` / claim CAS 抢占 / keyset 水位 /
  ack 语义 / 终态两处 UPDATE 单事务原子 / 进度短事务 / 文件只在 `executeExport` 内经 store 产出与清理。

## 对 phase9 的作废标注

- `2026-09-04-phase9-db-state-flow.md` 与 `2026-09-04-phase9-execute-export.md` 中：
  - 文件落点 `export.file-dir/export_<jobId>.xlsx`（flat、jobId 命名）→ **作废**，改 `root/<jobId>/export.xlsx`。
  - amount 走 numeric 单元格 → **作废**，改 STRING 单元格（固定 2 位小数字符串）。
  - job 级无 `finished_at`（只在 attempt 有）→ V6 起 export_jobs 补 `finished_at/file_path/file_size`。

## 给 phase 11 的接续点

- **下载/文件对外提供**：`ExportJobVO` 已含 `finishedAt`；`file_path/file_size` 仍留库中未返。真做下载时一行
  `store.resolve(job.getFilePath())` 完成越界校验即可 serving；若担心软链可对已存在文件 `toRealPath()` 二次 startsWith。
- **前端文件操作**：本期导出中心【完成时间】列随 SUCCESS/FAILED 自动点亮；如需展示文件大小/下载入口再扩展 VO 与前端。
- **"卡死 RUNNING"恢复 / 失败重试成新 attempt**：沿用 9.0 文档化的已知边界，留待后续。
- **历史 flat 文件**：9.0 遗留 `root/export_<id>.xlsx` 未迁移未清扫（本期明确不做）。
- **e2e 门禁**：本期以本地 docker 全栈 + 直插 SQL（特殊渠道自动 FAILED）与 ≥2500 行成功导出验证；后续阶段建议把这些沉淀为可重复执行的脚本/集成测试。
