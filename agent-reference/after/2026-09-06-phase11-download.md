# phase 11.0 after-doc：下载对外（下载接口 + 前端下载动作）— 供后续迭代

> 依据 `agent-reference/before/11.0.prompt.md` 实施。在 10.0（受控路径 + `.tmp` 原子发布 + `file_path/file_size`）产物之上接"下载对外"：
> 仅 SUCCESS 任务经受控路径对外提供文件，文件名经 `Content-Disposition`（RFC 6266/RFC 5987）编码传递；前端仅 SUCCESS 行显示并触发下载。
> **仍不提供失败重试 / 清扫 / 历史文件迁移。** 本文为 DB 状态流（phase9 文档）+ 文件流转变更（phase10 文档）之上的新增接续，不改 outbox/claim/ack/死信/文件发布机制。

## 一、下载接口契约

`GET /api/export-job/{id}/download`（无 body、无 query，前后端同源代理，无需 CORS 改动）。

**成功（200）响应**：
- `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- `Content-Disposition: attachment`，文件名交给 `ContentDisposition.attachment().filename(name, UTF_8).build()` 由 Spring 生成：
  含非 ASCII → `filename*=UTF-8''<百分号编码>`（可带 ASCII `filename=` 兜底）；纯 ASCII → 普通 `filename="..."`。**绝不手拼该头**（filename 来自用户输入，只有框架编码才防 CRLF/乱码）。
- `Content-Length` = 文件实际字节（`FileSystemResource.contentLength()`，与库中 `file_size` 恒等）
- body = `FileSystemResource` 流式返回文件字节（不整读进内存）

**拒绝（全部真实 HTTP 状态 + envelope JSON `ApiResponse{code,message,...}`，body 非文件）**：

| 情形 | HTTP | message |
|---|---|---|
| job 不存在 | 404 | 导出任务不存在 |
| status != SUCCESS（PENDING/RUNNING/FAILED 统一） | 409 | 任务尚未成功导出，暂不可下载 |
| `file_path` 为空 | 404 | 导出文件不存在 |
| `store.resolve` 越界（DB 存回相对路径被改） | 400 | 导出文件路径越界, 拒绝: … |
| 文件不存在 / 非普通文件 | 404 | 导出文件不存在，可能已被清理 |
| 读文件长度失败（IO） | 500 | 读取导出文件失败 |

> 非数字 `{id}` 命中 Spring 类型转换默认 400（非 envelope），前端失败分支已做 body 非 JSON 降级报错，接受，未额外加转换异常处理器。

## 二、R0：create 文件名兜底 `.xlsx`（下载名零特判的前提）

`ExportJobServiceImpl.normalizeFilename`：trim → 非空 → **未以 `.xlsx`（大小写不敏感）结尾则拼接 `.xlsx`** → 校验最终长度 ≤255。

- 效果：新任务入库名 / `ExportJobVO.filename`（创建响应 + 导出中心展示）/ 下载头文件名 **恒以 `.xlsx` 结尾**，下载接口直接原样复用库值，不做二次拼缀。
- 文件名**不入 `request_hash`**（hash 仅列+范围）→ 幂等/重试语义零影响；`normalizeFilename` 抛错在长度校验前（先拼后缀再查长度，255 字节边界文件名会因 +5 触界）。
- 历史已入库无后缀 SUCCESS 行**不回填**：下载原样返回旧名（属接受；本项目无历史数据则无感知）。
- 磁盘文件名恒为 `root/<jobId>/export.xlsx`，与展示名解耦，勿混。

## 三、`store.resolve` = 下载唯一路径守卫

- service 层 `downloadSource(jobId)`：`selectById` → 存在性 → SUCCESS 判定 → `file_path` 非空 → `store.resolve(filePath)` → `Files.isRegularFile`。**任何分支都不会拿未校验路径去碰磁盘**。
- `store.resolve` 的 `IllegalArgumentException` 必须在 service 捕获翻译成 `BizException(400)` —— **勿让裸 `IllegalArgumentException` 漏到 `GlobalExceptionHandler`**（那里映射 HTTP 200 + code=400，下载客户端会误判成功去存错误 body）。这是本阶段唯一新埋的"坑位"，其它异常收敛路径全部复用既有处理器。
- 下载入口不再另写软链追链/字符白名单（10.0 已定威胁面 = DB 存回相对路径被改，`normalize + startsWith` 足矣）。
- 全程只读：无 UPDATE、无事务、不碰 outbox/attempt/消费者，不读 `file_size` 做一致性校验（发布后文件只读不改，恒一致；Content-Length 直接读文件实际长度）。

## 四、前端解析 / 落盘路径

1. `src/utils/download.ts`：`parseDownloadFilename(contentDisposition)` 纯函数 —— 优先 `filename*=UTF-8''…` 做 `decodeURIComponent` 解码，畸形百分号回退普通 `filename=`；`saveBlob(blob, filename)` 用 `URL.createObjectURL` + `<a download>` 触发保存并 `revokeObjectURL`（唯一碰 DOM 处）。
2. `src/http/request.ts` 导出 `toApiError(res)`：解析失败 envelope（code/message/traceId），body 非 JSON 降级 `ApiError(HTTP <status>)` —— 下载失败与列表/创建走完全一致的错误语义。
3. `src/http/export.ts` `downloadExportJob(id)`：**裸 `fetch`**（不走会读 JSON 的 `request()`）；`res.ok` → `blob` + 头解析；否则 `throw await toApiError(res)`。
4. `src/pages/export-center/index.tsx`：操作列追加在模块级 `columns` 尾部（宽 90），**仅 `status === 'SUCCESS'` 行渲染"下载文件"**，其余行为空。`handleDownload` 模块级（下载是纯动作，无行级 state/loading）：成功 `saveBlob(blob, filename ?? job.filename ?? 'export_<id>.xlsx')`；失败 toast 后端原文。

文件名兜底顺序：响应头解析 → 行 `filename`（R0 起恒 `.xlsx`）→ `export_<id>.xlsx`。

## 五、测试与门禁（本次实测）

- 后端 `./mvnw compile` + `./mvnw test`（ExportFileStoreTest / OrderExportColumnsTest 等既有纯单测）全绿。
- 前端 `pnpm typecheck` 通过；`pnpm vitest run` 29 全绿，含新增 `utils/__tests__/download.test.ts` 4 例（star UTF-8 解码 / 纯 ASCII / 无头 null / 畸形百分号回退普通名）。
- 本阶段未做 docker 全栈 e2e（环境无运行中的 stack）：SUCCESS 200 / 409 / 404 / 400 / 文件被删 / `Content-Disposition` 中文名 / 只读性回归等 curl 断言见 `before/11.0.prompt.md` 第十节，后续阶段若起栈应按该清单打一遍。

## 六、给 phase 12 的接续点

- **文件清扫**：`ExportFileStore.deleteTaskDir` 已就绪（10.0 落地），何时清、按什么 retention 未定；清扫后下载走既有 404"可能已被清理"分支即可，前端无需预判。
- **失败重试成新 attempt**：沿用 9.0"卡死 RUNNING"已知边界与 attempt 表结构，重试需新增 claim 之外的入队路径（复用 outbox 或独立重试队列）。
- **大文件断点续传 / Range / 多版本文件**：现 `FileSystemResource` 全量下发；Range 需 controller 改 `HttpRange`/`ResourceRegion`。
- **批量下载（多任务 zip）**：需新增打包资源与文件清单接口。
- **下载审计**：现无日志埋点；如需可按 traceId 在 `downloadSource` 记一条 access log。
- **历史 flat 文件迁移**：10.0 遗留 `root/export_<id>.xlsx` 未迁移未清扫，`file_path` 为 NULL 的行下载即 404（接受口径），如需展示旧文件再做迁移。
