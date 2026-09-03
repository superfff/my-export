# 变更日志 - 2026-09-03 22:55

## 概述

按阶段 7.0 蓝图落地了三件事：**后端包结构重组**（把揉在订单域里的导出任务代码拆成平级模块，纯工程重构、行为不变）、**新增"导出中心"只读列表页**（前端页面 + 后端 `GET /api/export-job` 查询接口）、**修复订单页两处界面缺陷**（表格末行被分页器遮挡、排序下拉缺少默认值且可被清空）。全程无引入新依赖、无改表结构、无加 Flyway 迁移。

## 本次变更的目标

1. 后端包结构重组：`order` 模块就地保留，导出任务逻辑剥离成平级 `export` 模块，跨模块可复用件收进 `common`、基础设施独立成 `config`；启动类上移根包。
2. 新增导出中心只读列表：后端复用 `/api/export-job` 资源增补 GET 分页查询；前端新增「导出中心」菜单页 + 状态 tab + 10 列表格 + 底部分页，无任何行内按钮。
3. 修复订单管理 A1：表格数据超高时末行下半截被底部分页条裁掉。
4. 修复订单管理 A2：排序字段/方向默认「创建时间 / 降序」且不可清空，重置回默认。

## 变更详情

### backend（重构 + 列表接口）

#### backend/src/main/java/com/example/OrderApplication.java
- **修改类型**：修改（移动）
- **修改内容**：启动类从 `com.example.order` 上移到根包 `com.example`。
- **修改原因**：默认组件扫描范围变为 `com.example.*`，才能同时扫到平级拆出的 `order / export / common / config` 四个子包（重构）。

#### backend/src/main/java/com/example/common/*（ApiResponse / PageResult / GlobalExceptionHandler / TraceIdFilter / SortParamValidator / BizException）
- **修改类型**：修改（移动 + 更名）
- **修改内容**：六个类整体从 `com.example.order.common` 平移进 `com.example.common`；其中 `ExportBizException` **更名**为 `BizException` 并把类注释改为"通用业务异常"；`GlobalExceptionHandler` 的 handler 签名改为 `@ExceptionHandler(BizException.class)`、方法名 `handleExportBiz → handleBiz`。
- **修改原因**：这些是跨模块共用件（信封、分页、全局异常、过滤器、排序校验器），放订单包里会让 `export` 反向依赖 `order`。异常本身只携带 httpStatus+文案，不属 export 专属，故去 `Export` 前缀、迁入 common，避免 `common → export` 反向依赖（重构）。

#### backend/src/main/java/com/example/config/*（CorsConfig / FilterConfig / MybatisPlusConfig）
- **修改类型**：修改（移动）
- **修改内容**：三个基础设施配置类从 `com.example.order.config` 平移进 `com.example.config`；`FilterConfig` 里 `TraceIdFilter` 的 import 同步指向 `com.example.common.TraceIdFilter`。
- **修改原因**：`config` 是纯基础设施，与业务模块平级，各自独立（重构）。

#### backend/src/main/java/com/example/export/**（整个导出模块）
- **修改类型**：修改（移动）
- **修改内容**：`ExportJobController / ExportJobService / ExportJobServiceImpl / ExportJobMapper / ExportJob / ExportCreateRequest / ExportJobVO / ExportJobStatus / ExportMode` 九类从 `com.example.order.*` 各子包整体平移进 `com.example.export.{controller,service,service.impl,mapper,entity,dto,enums}`；同步改所有 package 声明与 import（`ExportJobServiceImpl` 补 `Order / OrderMapper` 为 `com.example.order.*`、`BizException` 为 `com.example.common.*`）。
- **修改原因**：导出任务是一块与订单并列的业务，理应自成 MVC 平级模块，不再混在订单包里（重构）。

#### backend/src/main/java/com/example/export/dto/ExportJobQueryDTO.java
- **修改类型**：新增
- **修改内容**：新增 `record ExportJobQueryDTO(String status, Long page, Long pageSize)`，承载列表查询参数。
- **修改原因**：导出中心列表接口的查询参数载体，参照订单页 `OrderQueryDTO` 的写法（业务功能）。

#### backend/src/main/java/com/example/export/service/ExportJobService.java
- **修改类型**：修改
- **修改内容**：接口新增 `PageResult<ExportJobVO> page(ExportJobQueryDTO query)`。
- **修改原因**：为导出中心列表查询暴露业务层方法（业务功能）。

#### backend/src/main/java/com/example/export/service/impl/ExportJobServiceImpl.java
- **修改类型**：修改
- **修改内容**：新增 `page()` 实现——status trim 后非空先校验 ∈ 四枚举，非法抛 `IllegalArgumentException`（走 HTTP200+code=400）；page/pageSize 归一（缺省/非法 → 1/20）；wrapper `eq(status)` + `orderByDesc(createdAt)` + `orderByDesc(id)`；分页查询后映射 VO。并把原 `create()` 末尾的内联拼 VO 抽成私有 `toVO()`，创建与列表共用同一套映射。
- **修改原因**：实现列表查询，同时消除"创建/列表两处各拼 VO"带来的口径漂移风险（业务功能 + 代码复用）。

#### backend/src/main/java/com/example/export/controller/ExportJobController.java
- **修改类型**：修改
- **修改内容**：在既有 `@PostMapping` 基础上新增 `@GetMapping` 的 `list(ExportJobQueryDTO)`，返回 `ApiResponse<PageResult<ExportJobVO>>`；javadoc 注明创建与列表共用 `/api/export-job` 资源。
- **修改原因**：按 RESTful 语义在同一个资源路径上增补只读列表，行对象复用现有 `ExportJobVO`、不另建第二套 VO（业务功能）。

#### backend/src/main/java/com/example/export/dto/ExportJobVO.java
- **修改类型**：修改
- **修改内容**：javadoc 由"创建成功返回"改为"创建与列表查询共用"，字段说明补全为四种状态。
- **修改原因**：该 VO 现被两个接口共用，注释要与实际用途一致（文档修正）。

#### backend/src/main/java/com/example/order/controller/OrderController.java、order/service/OrderService.java、order/service/impl/OrderServiceImpl.java
- **修改类型**：修改
- **修改内容**：对 `com.example.order.common.ApiResponse / PageResult / SortParamValidator` 的 import 改为 `com.example.common.*`；类本身原地不动。
- **修改原因**：这些共用件随重构迁去了 `common` 包，订单模块仅需修正引用路径（重构）。

### frontend（基础层 + A1/A2 + 导出中心页）

#### frontend/src/types/order.ts
- **修改类型**：修改
- **修改内容**：新增 `ExportCenterJob extends ExportJobVO`，扩展 4 个预留运行指标字段 `actualTotal / progress / finishedAt / fileSize`。
- **修改原因**：导出中心列表行类型。后端当前不返回这 4 个"真正导出阶段才填充"的指标，故全部可空、本期渲染 `-`（业务功能）。

#### frontend/src/constants/export.ts
- **修改类型**：修改
- **修改内容**：在既有 `EXPORT_409_MESSAGES` 旁并列新增 `EXPORT_MODE_TEXT`（导出模式→文案）、`EXPORT_JOB_STATUS`（状态→文案+颜色）、`EXPORT_JOB_STATUS_TABS` + `ExportStatusTab`（"全部"+四状态的 tab 选项）。
- **修改原因**：导出中心页需要展示口径；与后端枚举逐字一致、集中管理避免散落各处（业务功能）。

#### frontend/src/http/export.ts
- **修改类型**：修改
- **修改内容**：新增 `ExportJobListQuery` 与 `fetchExportJobs()`，复用底层 `get()` 调 `GET /api/export-job`；`status` 不传即"全部"。
- **修改原因**：导出中心页的数据请求入口；请求统一走 `http/` 保持低耦合（业务功能）。

#### frontend/src/components/PageTable/index.tsx
- **修改类型**：修改
- **修改内容**（A1 修复）：原来把 `.tableWrap` 整高直接当 `scroll.y`；改为测量后**减去固定表头高度**再回填 `scroll.y`（表头区块 + 滚动 body 叠放，总高比容器多出一个表头高，导致末行被裁）。无选择/有选择两条渲染分支共用同一份测量逻辑，仍由 `ResizeObserver` 驱动（勾选提示条显隐引起的高度变化仍自适应）。
- **修改原因**：Bug 修复。订单页与新建的导出中心都复用此组件，修一处两者同受益。

#### frontend/src/pages/order/OrderQueryForm.tsx
- **修改类型**：修改
- **修改内容**（A2 修复）：给表单加 `initialValues`，让 `sortField` 默认 `createdAt`、`sortOrder` 默认 `desc`；去掉这两个 Select 的 `allowClear`（不可清空，只能在两取值间切换）。
- **修改原因**：排序不该出现"空排序"态，用户打开页面即看到"创建时间/降序"（缺陷修复）。

#### frontend/src/pages/order/index.tsx
- **修改类型**：修改
- **修改内容**（A2 修复）：定义 `DEFAULT_SORT = { sortField: 'createdAt', sortOrder: 'desc' }`；查询初始 `useState` 与 `handleReset` 都落到它（reset 用展开新对象确保每次触发重查）。
- **修改原因**：让**首次加载起每次请求都显式携带** `sortField=createdAt&sortOrder=desc`，与表单默认一致、与后端兜底语义等价（缺陷修复）。

#### frontend/src/pages/export-center/index.module.css
- **修改类型**：新增
- **修改内容**：`.page / .headerRow / .header` 三件套布局样式，与订单页同款（满高 flex column、不滚动、两端对齐）。
- **修改原因**：导出中心页视觉对齐订单页。

#### frontend/src/pages/export-center/index.tsx
- **修改类型**：新增
- **修改内容**：导出中心页。左侧"导出中心"标题 + 右侧 `Segmented` 状态 tab（全部/PENDING/RUNNING/SUCCESS/FAILED）；10 列表头与需求逐字一致（任务编号~文件大小）；复用以 A1 修复后的 `PageTable` 且**不传**选择 props（走无选择分支）；占位列（实际条数/进度/完成时间/文件大小）缺省渲染 `-`；进度展示收敛为可测纯函数 `resolveExportProgress`；tab 切换回第 1 页、effect 内取消标志防竞态。
- **修改原因**：落地"导出中心"只读列表需求，明确本期不做任何按钮操作。

#### frontend/src/router/routes.tsx
- **修改类型**：修改
- **修改内容**：路由数组追加 `{ path: '/export-center', name: '导出中心', icon: <ExportOutlined />, component: ExportCenter }`。
- **修改原因**：菜单与页面切换都以 `routes` 为唯一数据源，注册后 SiderMenu 自动渲染菜单，`defaultPath` 仍为 `/order`。

## 关联说明

- **异常更名联动**：`ExportBizException → common/BizException` 是一次"改名即三方联动"——`GlobalExceptionHandler` 的 handler 签名/方法名、`ExportJobServiceImpl` 里所有 `throw new` 与 import 必须同步，否则编译/运行期不匹配。
- **后端结构重组 ↔ 前端零改动**：重构纯后端包级移动，接口路径/报文/表名/错误语义一律不变，前端无需感知，故前端本轮改动全部来自新功能与缺陷修复。
- **列表接口 ↔ 导出中心页**：后端 `GET /api/export-job`（`ExportJobQueryDTO → Service.page → Controller.list`，行对象复用 `ExportJobVO`）与前端 `ExportCenterJob 类型 + fetchExportJobs + export-center 页面 + 路由` 是"同一条链"的前后两端；`create()` 与列表共用 `toVO` 保证两接口 VO 口径一致。
- **A1 修复跨页面生效**：`PageTable` 是订单页与导出中心页共用的组件，高度算法修正后两个页面同时受益，这也是为何导出中心页"不自写高度测量"。
- **A2 前后端兜底对齐**：前端给默认值并不可清空、reset 回默认；后端 `OrderServiceImpl` 本就对"未显式传 sort"兜底为创建时间降序，两者等价、互为保险（后端本次未改代码，仅回归验证）。
- **表结构零变更**：为"导出中心 4 个无数据列"只做前端表头占位 + 缺省渲染 `-`，不动 `export_jobs` 表、不加 Flyway 迁移，避免给无人写入的列提前定型。

## 验证情况

- 后端 `./mvnw clean compile` 通过；应用启动后 curl 回归：创建导出任务、幂等 409 重复/冲突、缺 Key 400、订单列表默认降序、健康检查均与重构前一致。
- 列表接口 curl 矩阵：默认降序、status 过滤、空集合法、`status=FOO` → HTTP200+code400、小写容忍，全部符合预期。
- 前端 `pnpm typecheck / lint / test（13 用例）/ build` 全绿。
- 回归测试向 `export_jobs` 插入的临时行（id=12）已清理。
- **遗留人工验收项**：A1 末行不遮挡与 A2 默认值属于界面表现，需在浏览器（前端 http://localhost:5173、后端 http://localhost:8080 均在运行）肉眼确认；导出中心页路由/菜单/tab/分页亦建议人工走查。
