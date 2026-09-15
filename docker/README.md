# 中间件栈使用说明（docker/README.md）

本文件说明项目根目录 `docker-compose.yml` 里那套中间件的用途、启动方式、访问地址和常见问题。
下面按"这个中间件解决什么问题"来解释，不需要事先了解 Prometheus。

> 一句话总览：**Milvus 全家桶**负责 RAG 知识问答的向量检索；**Redis** 做缓存；
> **Prometheus + node-exporter** 负责"盯着机器看有没有出事"；**Alertmanager** 负责"出事了给谁打电话"，
> 它把告警转发给宿主机 9900 端口上的 Spring Boot 应用，由 AI Agent 自动诊断。

---

## 1. 每个中间件的作用

| 服务 | 作用 | 类比 |
| --- | --- | --- |
| **etcd** | 只存"目录信息"：有哪些知识库集合、分了多少片、建了什么索引 | 图书馆的**索引卡片柜** |
| **MinIO** | 存真正的向量数据和索引文件，几个 GB 的大块数据都在这 | 图书馆的**书库** |
| **Milvus** | 向量数据库本体。你把文档切片、算成向量存进去，提问时它按"语义相似度"把最相关的片段找出来 | **智能检索员** |
| **Attu** | Milvus 的网页版管理界面，点点鼠标就能看集合、查数据 | Milvus 的**遥控器** |
| **Redis** | 内存缓存 / 会话 / 任务状态，读写极快；开了 AOF，重启不丢数据 | **草稿纸 + 便签墙** |
| **node-exporter** | 一个"体检探头"：把机器的 CPU、内存、磁盘、网络数据翻译成一行行数字，等 Prometheus 来拿。它自己不做判断、不存数据 | **体温计** |
| **Prometheus** | 每 15 秒去探头那里抄一次数字，存进自己的时序数据库；同时按 `rules.yml` 里的规则判断"是不是超标了"。它只负责**发现问题和存数据**，不负责发通知 | **值班医生 + 病历本** |
| **Alertmanager** | 接收 Prometheus 报上来的问题，做去重、分组、静默，然后按配置发通知。默认只把告警交给飞书适配器转成群消息；需要 AI 诊断时在 `alertmanager.yml` 里放开应用那条路由 | **调度中心 / 打电话的人** |
| **feishu-adapter** | 飞书通知的格式转换：Alertmanager 的报文结构和飞书机器人要求的不一样，它负责把告警拼成飞书 `post` 富文本再转发到群机器人 | **翻译 + 传话** |

数据流向（默认）：

```
node-exporter(9100) --被抓取--> Prometheus(9090) --触发规则--> Alertmanager(9093)
                                                                   |
                                                                   | webhook POST
                                                                   v
                                                        飞书适配器 feishu-adapter:8080
                                                                   |
                                                                   v
                                                         飞书群机器人（post 富文本）
```

想在告警时**同时**跑 AI 自动诊断，把 `docker/alertmanager/alertmanager.yml` 里 `route.routes` 那段注释放开即可，
告警就会并行推给宿主机上的 Spring Boot（9900）跑多 Agent 排查：

```
Alertmanager ──> 应用 http://host.docker.internal:9900/api/alertmanager/webhook ──> AI Agent 自动诊断
```

---

## 2. 文件说明

```
OnCall/
├── docker-compose.yml                     # 全部中间件（Milvus 全家桶 + Redis + 监控栈）
├── volumes/                               # 【重要】Milvus 的真实数据（绑定挂载，别删）
│   ├── etcd/  ├── minio/  └── milvus/
└── docker/
    ├── README.md                          # 本文件
    ├── prometheus/
    │   ├── prometheus.yml                 # 去哪抓指标、规则文件在哪
    │   └── rules.yml                      # 5 条告警规则（与 aiops-docs/ 5 份手册一一对应）
    ├── alertmanager/
    │   └── alertmanager.yml               # 告警怎么分组、转发到哪些 URL（本服务 + 飞书适配器）
    ├── feishu-adapter/                    # 飞书通知适配器（零依赖 Node 脚本 + Dockerfile）
    │   ├── Dockerfile
    │   └── server.js
    └── publish-demo-alerts.ps1            # 手工推 5 条演示告警的脚本
```

---

## 3. 启动顺序与命令

**所有命令都要在项目根目录下执行**（因为 Milvus 的数据是相对路径绑定挂载）。

### 3.0 本机残留同名容器时先清理（可选）

如果这台机器以前用别的 compose 起过名字相同的 Milvus 容器（`milvus-etcd`、`milvus-minio`、
`milvus-standalone`、`milvus-attu`），`docker compose up -d` 会报容器名冲突。Docker 不允许同名容器，
先删掉残留的再启动：

```powershell
# 只删容器，不删数据（数据在 ./volumes 里，安全）
docker rm -f milvus-etcd milvus-minio milvus-standalone milvus-attu
# 旧网络也用不上了，可以一起删
docker network rm milvus
```

> 这一步只影响旧容器，**不会碰 `volumes/` 目录里的数据**。

### 3.1 常规命令

```powershell
# 启动全部中间件（-d = 后台运行）
docker compose up -d

# 查看状态（STATUS 里带 (healthy) 才是真的好了）
docker compose ps

# 看日志（-f 实时跟随；Milvus 首次启动较慢，健康检查有 90 秒 start_period，属正常）
docker compose logs -f milvus
docker compose logs -f prometheus
docker compose logs -f alertmanager

# 只重启某一个
docker compose restart attu

# 停止并删除容器（保留数据卷与 ./volumes 数据）
docker compose down

# 不要执行 docker compose down -v：-v 会删掉命名卷（Redis/Prometheus 数据）
```

### 3.2 启动顺序（compose 会自动处理，这里只是说明依赖关系）

```
etcd(健康) ┐
           ├─> milvus(健康) ─> attu
minio(健康)┘
node-exporter ─> prometheus ─> alertmanager（prometheus 只依赖它们“已启动”，
                                            真正的转发关系由 prometheus.yml 的 alerting 段配置）
redis（独立，随时可用）
```

Spring Boot 应用**不在容器里**，随便什么时候在宿主机上启动都行（`java -jar` 或 IDE 里跑，端口 9900）。

---

## 4. 访问地址与默认账号

| 服务 | 地址 | 默认账号 / 说明 |
| --- | --- | --- |
| **Milvus** | `localhost:19530` | 无账号密码（`application.yml` 里就是空字符串）；HTTP 指标口 `localhost:9091` |
| **Attu（Milvus 界面）** | http://localhost:8000 | 容器内是 3000，宿主机映射成 **8000**（宿主机 3000 被腾讯云日志 MCP 占用，**不能占用**） |
| **MinIO 控制台** | http://localhost:9001 | 账号 `minioadmin` / 密码 `minioadmin`（S3 API 在 9000） |
| **Redis** | `localhost:6379` | 无密码；`docker exec -it oncall-redis redis-cli ping` 应返回 PONG。应用（`spring.data.redis` + `chat.memory.store: redis`）用它持久化对话上下文（键前缀 `chat:session:`）；Redis 没起来时应用会自动降级成内存存储，**不会启动失败**，但重启后会话就丢了 |
| **Prometheus** | http://localhost:9090 | 查指标、看 `Status → Rules` 里的 5 条规则、`Alerts` 里看告警状态 |
| **Alertmanager** | http://localhost:9093 | 看当前告警、静默；接口 `GET /api/v2/alerts` |
| **node-exporter** | http://localhost:9100/metrics | 原始指标文本，`/` 页面有简单链接 |
| **飞书适配器** | http://localhost:8080 | 返回 `{"status":"ok","configured":true/false}`；`configured:false` 表示还没填飞书机器人地址（此时只打印不发送） |
| Spring Boot 应用 | http://localhost:9900 | 不在容器里，跑在 Windows 宿主机上 |

---

## 5. 怎么演示"告警自动通知 / 自动诊断"

### 5.1 演示原理

真把 CPU 跑到 92% 又慢又危险，所以提供脚本 `docker/publish-demo-alerts.ps1`，
它直接调用 Alertmanager 的 HTTP 接口，手工"灌"进 5 条告警。

**默认行为**：Alertmanager 把告警交给飞书适配器，群里收到通知，**不会**触发 AI 排查。
想让告警同时触发 AI 自动诊断，先放开 `docker/alertmanager/alertmanager.yml` 里 `route.routes` 那段注释
（放开后每条告警都会跑一轮多 Agent 排查，5 条演示告警 ≈ 3~5 分钟、消耗若干次大模型调用）。

5 条告警与运维手册的对应关系：

| alertname | service（演示值） | severity | 对应手册 |
| --- | --- | --- | --- |
| `HighCPUUsage` | payment-service | critical | `aiops-docs/cpu_high_usage.md` |
| `HighMemoryUsage` | order-service | critical | `aiops-docs/memory_high_usage.md` |
| `HighDiskUsage` | mysql | warning | `aiops-docs/disk_high_usage.md` |
| `ServiceUnavailable` | gateway-service | critical | `aiops-docs/service_unavailable.md` |
| `SlowResponse` | user-service | warning | `aiops-docs/slow_response.md` |

### 5.2 操作步骤

```powershell
# 1) 启动 Alertmanager 与飞书适配器（如果整套还没起）
docker compose up -d alertmanager feishu-adapter

# 2) 先干跑一次，只打印将要发送的 JSON，检查结构
.\docker\publish-demo-alerts.ps1 -DryRun

# 3) 正式推送 5 条演示告警
.\docker\publish-demo-alerts.ps1

# 4) 查看结果
#    浏览器打开 http://localhost:9093 看这 5 条告警
#    飞书群里应该收到 5 条通知（group_by 按 alertname + service 分成了 5 组）
#    看适配器转发结果：docker compose logs -f feishu-adapter
```

脚本每次运行都会在标签里带上唯一的时间戳+随机数（`demo_run`），并给每条告警写入唯一的
`fingerprint`，所以可以**反复运行**，不会被 Alertmanager 当成重复告警丢掉。

> **要连 AI 自动诊断一起演示**：放开 `docker/alertmanager/alertmanager.yml` 里 `route.routes` 那段注释，
> 重启 Alertmanager 后，告警会同时推给宿主机应用（9900）。接口是 `POST /api/alertmanager/webhook`
> （见 `src/main/java/org/example/alert/AlertWebhookController.java`，开关在 `application.yml` 的
> `alert.webhook` 段）。它只做校验、去重、入队，然后**立刻返回 200**，真正的 AIOps 排查在后台异步执行
> （一轮要几十秒，同步等会导致 Alertmanager 超时重发）。
>
> **怎么看诊断结果**（自动诊断没有前端在等结果，所以提供了查询接口）：
>
> ```powershell
> # 最近的自动诊断记录列表
> curl http://localhost:9900/api/ai_ops/auto/runs
> # 单条诊断详情（含完整报告），runId 从上面的列表里取
> curl http://localhost:9900/api/ai_ops/auto/runs/{runId}
> # 实时事件流（SSE），可以实时看到诊断进展
> curl -N http://localhost:9900/api/ai_ops/auto/stream
> ```
>
> **两个注意点**：
> 1. 应用侧有 **30 分钟去重窗口**（`alert.webhook.dedup-ttl-minutes: 30`），同一告警指纹在窗口内
>    重复推送会被判为重复而跳过诊断。脚本给每条告警都带了唯一的 `demo_run` 标签，
>    所以可以反复演示而不被去重吃掉——这也是脚本"可重复运行"的关键。
> 2. 应用没启动时，告警照样能进 Alertmanager（界面可见、脚本也报成功），
>    只是转发会失败并自动重试，日志里长这样：
>    `Notify attempt failed ... dial tcp 192.168.65.254:9900: connect: connection refused`
>    （`192.168.65.254` 就是 `host.docker.internal` 在 Docker Desktop 里解析出的宿主机地址）。

### 5.3 让告警同时发到飞书

告警链路**默认只走飞书**（`alertmanager.yml` 的 `route.receiver: feishu-webhook`）：

| 接收器 | 作用 | 默认是否接线 |
| --- | --- | --- |
| `feishu-webhook` | POST 给 `feishu-adapter:8080`，转成飞书消息发到群 | ✅ 接线 |
| `oncall-agent-webhook` | POST 给宿主机 9900，触发 AI 自动诊断 | ❌ 未接线（`route.routes` 里放开即启用） |

想让告警同时触发 AI 诊断，放开 `route.routes` 那段注释即可——里面用 `continue: true`
让同一个告警匹配下一个兄弟路由，于是飞书那条也照常收到。

**为什么不让 Alertmanager 直接发飞书**：Alertmanager 的 webhook 报文是它自己的结构（`version/status/groupKey/alerts[]`），
飞书机器人只认 `msg_type/content`，直接填飞书地址会被飞书拒掉（返回 400）。所以中间需要一个适配器。

**配置步骤**：

1. 在飞书群里「设置 → 群机器人 → 添加机器人 → 自定义机器人」，复制它的 Webhook 地址
   （形如 `https://open.feishu.cn/open-apis/bot/v2/hook/xxxx`）。
   如果创建时勾了「签名校验」，把密钥也记下来。
2. 填进 `docker-compose.yml` 的 `feishu-adapter` 服务：

   ```yaml
   environment:
     FEISHU_WEBHOOK_URL: "https://open.feishu.cn/open-apis/bot/v2/hook/xxxx"
     FEISHU_SECRET: ""      # 没开签名校验就留空
   ```

3. 重建并重启适配器：

   ```powershell
   docker compose up -d --build feishu-adapter
   ```

4. 验证：

   ```powershell
   # configured 变成 true 说明地址已生效
   curl http://localhost:8080
   # 推一条演示告警，飞书群里应该收到一张卡片式富文本
   .\docker\publish-demo-alerts.ps1
   # 看适配器转发结果（飞书返回 {"code":0,"msg":"success"} 即成功）
   docker compose logs -f feishu-adapter
   ```

> 地址**留空也能跑**：适配器会把将要发送的消息打到日志里（`[dry-run]`），
> 方便先验证"Alertmanager → 适配器"这一段通不通，再去申请机器人。
> 消息形态是飞书 `post` 富文本：标题带 🚨（恢复时是 ✅），正文逐条列出告警名、服务、级别、摘要和实例。

---

## 6. 常见问题（FAQ）

### Q1. 启动报端口被占用（port is already allocated）

```powershell
# 看是哪个进程占了端口（把 9090 换成报错里的端口）
netstat -ano | findstr ":9090"
# 再用 PID 查进程
tasklist | findstr "<PID>"
```

- 本项目占用的宿主机端口：`19530、9091、9000、9001、8000、6379、9090、9093、9100`。
- **宿主机 3000 端口已被"腾讯云日志 MCP"占用**，所以本文件里 Attu 用的是 `8000:3000`
  （容器里仍然是 3000，只是对外映射到 8000）。如果你自己再写 compose 文件，千万别映射 3000。
- 如果 9090 / 9093 / 9100 / 6379 被别的监控工具占了，改 `docker-compose.yml` 里冒号**左边**的数字即可
  （例如 `"19090:9090"`）；但记得 Prometheus 抓取目标用的是容器内端口，不受影响。

### Q2. Milvus 起不来（一直 unhealthy / 反复重启）

按顺序排查：

1. **etcd、minio 是否健康**：`docker compose ps` 里它们必须是 `(healthy)`，Milvus 依赖这两个。
   ```powershell
   docker compose logs --tail 100 etcd
   docker compose logs --tail 100 minio
   ```
2. **数据目录权限/占用**：Milvus 是绑定挂载 `./volumes/...`，确认这三个目录存在且没被别的程序锁住。
3. **首次启动慢是正常的**：健康检查有 `start_period: 90s`，等 1~2 分钟再看。
4. **看 Milvus 自己的日志**：
   ```powershell
   docker compose logs --tail 200 milvus
   ```
5. **容器名冲突**：如果报 `container name "/milvus-etcd" is already in use`，
   说明本机还留着别的 compose 起的同名容器，按本文 3.0 节删掉再启动。

### Q3. Attu 退出了 / 状态是 Exited (1)

Attu 是"纯前端"，它启动时会去连 Milvus，Milvus 没就绪时它可能直接退出（状态 `Exited (1)`）。
处理办法：

```powershell
# 确认 Milvus 已经 healthy
docker compose ps milvus
# 再单独把 Attu 重启一下
docker compose restart attu
docker compose logs --tail 50 attu
```

`docker-compose.yml` 里已经写了 `depends_on: milvus: condition: service_healthy`，
正常情况下 Attu 会等 Milvus 健康后才启动。如果 Milvus 后来才恢复，手动 `restart attu` 即可。

### Q4. 数据到底存在哪里？`./volumes/` 和命名卷有什么区别？

| 类型 | 用在哪 | 实际位置 | 特点 |
| --- | --- | --- | --- |
| **绑定挂载**（`./volumes/etcd`、`./volumes/minio`、`./volumes/milvus`） | Milvus 全家桶 | 就在项目目录 `volumes/` 下，**能直接用资源管理器看到** | 有几 GB 真实向量数据，`docker compose down` 不会删；**不要手动删目录** |
| **命名卷**（`redis-data`、`prometheus-data`、`alertmanager-data`） | Redis、Prometheus、Alertmanager | Docker 自己管理的区域（Windows 上在 Docker Desktop 的虚拟磁盘里） | 用 `docker volume ls` 查看；`docker compose down` 保留，`down -v` 才会删 |

```powershell
# 查看命名卷的真实位置
docker volume inspect oncall-agent_prometheus-data
```

> Milvus 之所以用绑定挂载，就是为了数据"看得见、搬得走、不会被 `-v` 顺手删掉"。
> 迁移机器时，把整个项目目录（含 `volumes/`）拷过去就行。

### Q5. Prometheus 里看不到数据 / 告警一直是 Inactive

1. 打开 http://localhost:9090/targets ，两个 job（`prometheus`、`node`）应该都是 **UP**。
   - `node` 是 DOWN：`docker compose ps node-exporter` 看容器是否在跑。
2. 打开 http://localhost:9090/rules ，确认 5 条规则都加载成功（配置写错时 Prometheus 会启动失败，
   日志里 `docker compose logs prometheus` 能看到具体行号）。
3. 想让规则真的触发，需要机器指标真的超过阈值；演示请直接用 `publish-demo-alerts.ps1`。

### Q6. 告警为什么没有转发到 Spring Boot 应用？

1. 应用是否在宿主机上启动并监听 9900：`netstat -ano | findstr ":9900"`。
2. 从 Alertmanager 容器内部能不能访问宿主机 9900（镜像里有 busybox 的 wget）：
   ```powershell
   docker compose exec alertmanager wget -qO- http://host.docker.internal:9900/
   ```
   - 输出 HTML 内容 = 网络通、应用在跑；
   - `can't connect to remote host (192.168.65.254): Connection refused` = 网络通但应用没启动；
   - `bad address` = DNS 没解析（Linux 上检查 `extra_hosts` 是否生效）。
   Docker Desktop（Windows/Mac）自带 `host.docker.internal`；Linux 上靠 `docker-compose.yml` 里的
   `extra_hosts: ["host.docker.internal:host-gateway"]` 支持（本文件已加）。
3. 看 Alertmanager 日志里的转发错误：`docker compose logs -f alertmanager`。
4. 看应用日志里有没有 `收到 Alertmanager 告警推送: 条数=5, 分组=...`。
   接口定义在 `src/main/java/org/example/alert/AlertWebhookController.java`；
   如果日志里是 `收到告警推送，但 alert.webhook.enabled=false，已忽略`，
   把 `application.yml` 里的 `alert.webhook.enabled` 改成 `true` 重启应用即可。
5. 确认告警没被应用侧的 30 分钟去重窗口吃掉：脚本每次运行都会换 `demo_run` 标签，
   但如果手动用同一份 JSON 反复推，第二次起就会被判定为重复而跳过诊断。

### Q7. 需要说明的几点

- **`SlowResponse` 规则是演示占位**：node-exporter 只提供 CPU/内存/磁盘/网络等系统指标，
  **没有** HTTP 响应时间指标，所以 `rules.yml` 里这条规则用一个物理上不可能达到的阈值
  （网络吞吐 > 1 TB/s）占位，不会误报。真实的响应时间告警需要 `blackbox_exporter` 或应用侧
  指标（`http_server_requests_seconds` 的 P99）。
- **Docker Desktop for Windows 上 node-exporter 看到的是 Linux 虚拟机的指标，不是 Windows 宿主机本身**。
  也就是说，`HighCPUUsage`/`HighMemoryUsage` 反映的是 Docker 后端那台 Linux VM 的负载，
  不会因为你 Windows 上开个大程序就报警。演示请用推送脚本。
- 本项目的 Spring Boot 应用目前没引入 `actuator` + `micrometer-registry-prometheus`，
  所以 `prometheus.yml` 里针对应用自身指标的 job 是**注释掉的**，等加了依赖再打开。

---

## 7. 自检命令（改完配置后跑一下）

```powershell
# 1) 校验 compose 文件语法（只解析，不启动）
docker compose config --quiet

# 2) 校验 Prometheus 规则文件语法（规则写错 Prometheus 会起不来）
#    注意必须加 --entrypoint promtool：镜像默认入口是 prometheus 本体，直接写 promtool 会被当成非法参数
docker run --rm --entrypoint promtool -v "${PWD}/docker/prometheus/rules.yml:/rules.yml:ro" prom/prometheus:v2.54.1 check rules /rules.yml

# 3) 校验 Prometheus 主配置（会连带校验 rule_files 里的 rules.yml）
docker run --rm --entrypoint promtool -v "${PWD}/docker/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro" -v "${PWD}/docker/prometheus/rules.yml:/etc/prometheus/rules.yml:ro" prom/prometheus:v2.54.1 check config /etc/prometheus/prometheus.yml

# 4) 校验 Alertmanager 配置（同样要 --entrypoint amtool）
docker run --rm --entrypoint amtool -v "${PWD}/docker/alertmanager/alertmanager.yml:/tmp/am.yml:ro" prom/alertmanager:v0.28.1 check-config /tmp/am.yml

# 5) 检查演示脚本会发什么（不真的推送）
.\docker\publish-demo-alerts.ps1 -DryRun
```
