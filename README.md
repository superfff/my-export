# 订单导出后台（全栈）

一个端到端的订单管理示例：React 前端调用 Spring Boot 后端查询 MySQL 里的订单数据。整个工程用 Docker 编排，拿到代码后一条命令就能部署。

## 技术栈

| 层 | 技术 |
|----|------|
| 前端 | React 18 + Vite + TypeScript + Ant Design 5 + fetch |
| 后端 | Java 21 + Spring Boot 3 + MyBatis-Plus（MVC 分层） |
| 数据库 | MySQL 8.4 |
| 部署 | Docker Compose（前端 nginx + 后端 + MySQL） |
| 包管理 | 前端 pnpm / 后端 Maven |

## 目录结构

```
my-export/
├── frontend/                 # 前端
│   ├── src/http/             #   fetch 底层封装 + 订单接口
│   ├── src/pages/order/      #   订单管理页
│   ├── src/components/       #   复用组件（表格 + 分页）
│   ├── src/types/            #   类型定义
│   ├── Dockerfile / nginx.conf
│   └── vite.config.ts        #   开发环境 /api 代理
├── backend/                  # 后端
│   ├── src/main/java/com/example/order/
│   │   ├── controller/       #   接口层
│   │   ├── service/          #   业务层
│   │   ├── mapper/           #   数据访问层
│   │   ├── dto/              #   请求/响应 DTO
│   │   ├── entity/           #   数据库实体
│   │   ├── common/           #   统一响应、分页
│   │   └── config/           #   MyBatis-Plus / CORS 配置
│   ├── src/main/resources/application.yml
│   └── Dockerfile
├── docker/mysql/init/01-init.sql   # 建表 + 种子数据
└── docker-compose.yml              # 一键编排 mysql + 后端 + 前端
```

## 快速开始（Docker 一键部署）

只需要本机装了 **Docker**，不需要装 Java、Maven、Node、pnpm。

```bash
# 1. 在项目根目录执行
docker compose up -d --build
```

三个容器会依次启动：MySQL → 后端 → 前端。首次启动要拉镜像、编译，需要几分钟。

```bash
# 2. 查看启动状态（等 mysql 变 healthy、backend/frontend 变 running）
docker compose ps
```

启动完成后：

- 打开 **http://localhost:5173** → 订单管理页，表格会自动加载后端返回的 30 条订单数据
- 后端健康检查：http://localhost:8080/actuator/health
- 直接调接口：http://localhost:8080/api/order?page=1&pageSize=20

停止：`docker compose down`；连数据一起清空：`docker compose down -v`。

## 本地开发（可选，前后端分开跑）

适合改代码时实时热更新。需要本机装 **Node + pnpm + JDK21**（Maven 已通过项目内的 Maven Wrapper 自带，无需单独安装，首次执行 `./mvnw` 会自动下载）。

```bash
# 1. 只启动 MySQL（数据会由 init 脚本自动建表 + 灌入种子数据）
docker compose up -d mysql

# 2. 启动后端（另开一个终端）
cd backend
./mvnw spring-boot:run

# 3. 启动前端（另开一个终端）
cd frontend
pnpm install
pnpm dev        # http://localhost:5173
```

前端 `vite.config.ts` 里配置了代理，把 `/api` 和 `/actuator` 转发到 `http://localhost:8080`，所以本地开发也不会跨域。

## 接口说明

统一前缀 `/api`，统一响应结构：

```json
{ "code": 0, "message": "ok", "data": { ... } }
```

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/order` | 订单分页查询 |
| GET | `/actuator/health` | 后端健康检查 |

`GET /api/order` 支持的 query 参数（都可选）：

| 参数 | 含义 |
|------|------|
| orderNo / customerName / phone | 模糊匹配 |
| status | 精确匹配（1 未支付 / 2 已支付 / 3 已取消） |
| startTime / endTime | 创建时间范围（毫秒时间戳） |
| page / pageSize | 分页，默认 1 / 20 |

示例：

```bash
curl 'http://localhost:8080/api/order?status=2&page=1&pageSize=10'
```

## 数据库表 t_order

字段（下划线命名的部分是你没列、我帮你补全的）：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键，自增（补全） |
| order_no | VARCHAR | 订单号（你提供） |
| customer_name | VARCHAR | 客户名称（你提供） |
| phone | VARCHAR | 客户手机号（你提供） |
| status | TINYINT | 状态 1/2/3（你提供） |
| amount | DECIMAL(12,2) | 订单金额（补全） |
| created_at | DATETIME | 创建时间（你提供） |
| updated_at | DATETIME | 更新时间（补全） |
| remark | VARCHAR | 备注（补全） |

## 请求是怎么从浏览器走到数据库的（分步讲解）

一次「订单查询」完整的调用链：

```
浏览器(前端)                          后端                          数据库
  │ 点击查询 / 首次进入页面
  │ fetch('/api/order?page=1...')
  │      │
  │      │  (开发环境：Vite 代理；Docker：nginx 反向代理)
  │      ▼
  │   OrderController  ───────►  OrderServiceImpl  ───────►  OrderMapper
  │   /api/order             拼接 LambdaQueryWrapper     BaseMapper.selectPage
  │      │                        （拼 where 条件）           （执行分页 SQL）
  │      │                                                     │
  │      │                                                     ▼
  │      │                                                   MySQL t_order
  │      │                                                     │
  │      │   OrderVO  ◄─────────  PageResult ◄─────────────── 返回 30 条 + total
  │      ▼
  │   { code:0, data:{ list:[...], total:30 } }
  │      │
  │  setList / setTotal 更新表格
```

对应到代码：

1. **前端 `src/http/order.ts`** 的 `fetchOrders` 发起 GET 请求，带上筛选条件。
2. **前端 `src/pages/order/index.tsx`** 拿到 `{ list, total }` 后渲染表格和分页。
3. **后端 `OrderController`** 接收请求，把 query 参数绑定成 `OrderQueryDTO`，交给 service。
4. **后端 `OrderServiceImpl`** 用 `LambdaQueryWrapper` 拼出「哪个字段传了就按哪个字段过滤」的 where 条件，再调 `selectPage` 分页查询。
5. **后端 `OrderMapper`**（继承 MyBatis-Plus 的 `BaseMapper`）执行真正的 SQL，从 `t_order` 表取数据。
6. 结果装进 `OrderVO`，再包一层 `ApiResponse`（code=0）返回给前端。

这样分层的好处：前端只关心「发请求、拿数据」，后端每一层只做一件事，改了某一层不影响其他层。
