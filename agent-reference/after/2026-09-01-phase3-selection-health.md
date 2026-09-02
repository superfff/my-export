# 变更日志 - 2026-09-01 17:30

## 概述

根据 3.0 阶段需求，实现四项目标：查询条件空值保护、表格勾选（跨页保留 + 顶部提醒）、表头双模式选择（手动勾选 / 全选）、后端健康检测定时器及右上角异常提示。

## 变更详情

### frontend/src/pages/order/index.tsx

- **修改类型**：修改
- **修改内容**：
  1. `handleSearch` 增加空值过滤逻辑，遍历表单值只保留有效字段（非 `undefined`/`null`/空字符串），`createdRange` 增加两项存在性校验避免产生 `NaN`
  2. 新增 `selectionState` 状态（`SelectionState` 类型），由 `createInitialSelectionState()` 初始化
  3. 查询、重置时调用 `setSelectionState(createInitialSelectionState())` 重置勾选
  4. 向 `PageTable` 传入 `selectionState`、`onSelectionChange`、`filteredTotal` 三个新 props
- **修改原因**：目标 1 防止无效查询条件传给后端；目标 2/3 需要页面层维护勾选状态以实现跨页保留和双模式选择；查询条件变化时勾选应清空，避免勾选数据与筛选条件不一致

### frontend/src/types/order.ts

- **修改类型**：修改
- **修改内容**：新增 `SelectionMode` 枚举（`MANUAL` / `ALL`）和 `SelectionState` 接口（`mode` + `selectedIds: Set<number>` + `excludedIds: Set<number>`）
- **修改原因**：目标 2/3 需要类型定义来描述双模式选择状态。手动模式用 `selectedIds` 存勾选 id，全选模式用 `excludedIds` 存反选 id，两种模式的数据结构不同，用联合接口 + mode 字段区分

### frontend/src/components/PageTable/index.tsx

- **修改类型**：修改
- **修改内容**：
  1. 新增 props：`selectionState`、`onSelectionChange`、`filteredTotal`
  2. 导出 `createInitialSelectionState()` 工厂函数
  3. 无选择 props 时渲染基础表格（向后兼容）
  4. 有选择 props 时：在 columns 最前面插入选择列（Checkbox），自定义表头包含全选 Checkbox + 模式切换下拉（"手动" ▾ / "全选" ▾）
  5. 手动模式逻辑：勾选加入 `selectedIds`，取消移除；表头全选控制当前页
  6. 全选模式逻辑：默认全选当前页，反选加入 `excludedIds`，恢复则从 `excludedIds` 移除；表头全选控制当前页全部恢复/全部反选
  7. 模式切换时清空 `selectedIds` 和 `excludedIds`
  8. 勾选数 > 0 时在表格上方渲染"已勾选 N 条"提醒条；全选模式额外显示"（全选模式）"标签
  9. 勾选数计算：手动模式 = `selectedIds.size`，全选模式 = `filteredTotal - excludedIds.size`
- **修改原因**：目标 2 要求跨页保留勾选 + 顶部提醒，勾选状态由父组件持有、组件只负责渲染和事件上报；目标 3 要求表头支持两种选择模式，全选模式只记录反选 id 以节省内存（不需要存所有数据的 id）

### frontend/src/components/PageTable/PageTable.module.css

- **修改类型**：修改
- **修改内容**：新增 `.selectionNotice`（提醒条样式，蓝色背景边框）、`.selectionCount`（数字高亮蓝色加粗）、`.modeTag`（全选模式标签）、`.headerCell`（表头选择区 inline-flex 布局）、`.modeSwitch`（模式切换文字可点击蓝色）、`.row`（行 cursor:pointer）
- **修改原因**：目标 2/3 新增的勾选提醒条和表头模式切换 UI 需要对应样式

### frontend/src/http/health.ts

- **修改类型**：新增
- **修改内容**：`checkBackendHealth()` 函数，直接 `fetch('/actuator/health')`，解析 `{status}` 字段，`status === 'UP'` 返回 `true`，否则返回 `false`；请求失败（网络错误、非 2xx）也返回 `false`
- **修改原因**：目标 4 需要前端定时检测后端健康。`/actuator/health` 是 Spring Actuator 原始 JSON 格式（`{status:"UP"}`），不是业务接口的 `{code, message, data}` 包装，所以不能走 `http/request.ts` 的统一响应解析，需要单独用 fetch 处理

### frontend/src/hooks/useBackendHealth.ts

- **修改类型**：新增
- **修改内容**：`useBackendHealth()` 自定义 Hook，内部用 `setInterval` 每 2000ms 调用 `checkBackendHealth()`，返回 `{ isHealthy: boolean }`；首次 mount 时立即检查一次；组件卸载时 `clearInterval` 清理定时器
- **修改原因**：目标 4 要求每 2 秒查询一次后端健康状态。用自定义 Hook 封装定时器逻辑，便于在 App 顶层调用，且自动管理定时器生命周期

### frontend/src/App.tsx

- **修改类型**：修改
- **修改内容**：
  1. 引入 `useBackendHealth` Hook 和 antd `Alert` 组件
  2. 在 App 组件中调用 `useBackendHealth()`，获取 `isHealthy`
  3. `isHealthy === false` 时在布局内渲染固定定位的 `Alert`，文案"后端服务异常"，type=error，banner 模式
- **修改原因**：目标 4 要求后端异常时在管理系统右上角提示。App 是顶层组件，在这里接入健康检测和全局提示可以覆盖所有页面

### frontend/src/App.module.css

- **修改类型**：修改
- **修改内容**：新增 `.healthAlert` 样式（`position: fixed; top: 0; right: 0; z-index: 1000`，最小宽度 200px）和 `.healthAlert :global(.ant-alert)` 圆角样式
- **修改原因**：目标 4 要求异常提示固定在右上角，需要 `position: fixed` 定位到视口右上角，不影响页面布局流

## 关联说明

1. **类型定义 → 组件 → 页面**：`types/order.ts` 新增的 `SelectionMode` / `SelectionState` 被 `components/PageTable` 和 `pages/order/index` 共同引用，三者是"定义-渲染-状态持有"的上下游关系，修改必须同步
2. **健康检查 → Hook → App**：`http/health.ts` 提供底层请求函数 → `hooks/useBackendHealth.ts` 封装定时器逻辑 → `App.tsx` 调用 Hook 并渲染提示 UI，三层链式依赖
3. **查询条件变更 → 勾选重置**：`pages/order/index.tsx` 中 `handleSearch` 和 `handleReset` 会调用 `setSelectionState(createInitialSelectionState())`，因为查询条件变化后，之前勾选的数据可能已不在结果中，必须清空避免数据不一致
4. **PageTable 向后兼容**：不传 `selectionState`/`onSelectionChange` 时，PageTable 渲染无选择列的基础表格，与修改前的行为完全一致
