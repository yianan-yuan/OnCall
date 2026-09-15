# OnCall

> 基于 Spring Boot + Spring AI 的智能运维助手：RAG 知识问答 + AIOps 智能排查 + 飞书告警通知

## 📖 项目简介

一个本地就能跑通的智能 OnCall 助手，包含三块能力：

- **RAG 智能问答**：文档入库后在 Milvus 里做向量检索，同时用 BM25 做关键词检索，两路结果融合后再精排，支持多轮对话与流式输出；
- **AIOps 智能运维**：多 Agent 协作排查，自动查 Prometheus 告警、查日志、检索运维手册，最后产出结构化的告警分析报告；
- **告警通知**：Prometheus 规则一触发，Alertmanager 就把告警转成飞书群消息（含告警名、服务、级别、摘要）。

## 🚀 核心特性

- ✅ **混合检索**：Milvus 向量召回 + BM25 关键词召回双路并行 + RRF 融合 + 精排
- ✅ **上下文记忆**：滑动窗口 + 增量摘要 + 压缩水位线，会话存 Redis，长对话不会撑爆上下文窗口
- ✅ **多 Agent 排查**：Planner 拆解任务 → Executor 执行 → 汇总成告警分析报告，页面可展开"查看详细步骤"
- ✅ **告警通知**：Alertmanager 收到告警后推送到飞书群（富文本，带颜色区分严重级别）
- ✅ **流式输出**：对话与 AIOps 排查过程都通过 SSE 实时推送到页面
- ✅ **Web 界面**：内置测试界面，支持快速/流式两种模式，带上下文占用环

## 🛠️ 技术栈

| 技术 | 说明 |
| --- | --- |
| Java 17 / Spring Boot 3.2 | 应用框架 |
| Spring AI + Spring AI Alibaba | 模型接入与 Multi-Agent 编排（ReactAgent / SupervisorAgent） |
| DeepSeek + 阿里云百炼 | 对话与编排走 DeepSeek；向量化与精排走阿里云（DeepSeek 没有 embedding / rerank 接口） |
| Milvus 2.6 | 向量数据库 |
| Redis | 会话与上下文记忆持久化 |
| Prometheus + Alertmanager | 指标采集、告警规则、告警路由 |
| Docker Compose | 一键拉起全部中间件 |

## 📡 常用接口

| 接口 | 说明 |
| --- | --- |
| `POST /api/chat` | 普通对话（一次性返回） |
| `POST /api/chat_stream` | 流式对话（SSE） |
| `POST /api/ai_ops` | 触发 AIOps 多 Agent 排查（SSE，可带 `sessionId` 把报告写回会话） |
| `POST /api/upload` | 上传文档并自动分片、向量化写入 Milvus |
| `GET /api/chat/session/{id}/memory` | 查看某会话的上下文占用、摘要与存储介质 |
| `POST /api/chat/session/{id}/compact` | 手动压缩上下文（把较早对话并入摘要） |

## 📦 目录结构

```
OnCall/
├── src/main/java/org/example/
│   ├── retrieval/         # 分词、BM25L 打分、RRF 融合
│   ├── service/           # 混合检索流水线、向量服务、对话与 AIOps 服务
│   ├── memory/            # 上下文记忆（滑动窗口 + 摘要 + Redis 存储）
│   ├── alert/             # 告警 Webhook 接收、去重与并发限流
│   ├── agent/tool/        # Agent 工具：文档检索、告警查询、日志分析、时间
│   └── controller/        # HTTP / SSE 接口
├── src/main/resources/
│   ├── static/            # 前端页面（对话、AIOps、上下文环）
│   └── application.yml    # 主配置（密钥用环境变量占位）
├── src/test/java/         # 单元测试
├── docker/                # Prometheus 规则、Alertmanager 配置、飞书适配器
├── aiops-docs/            # 演示用运维手册（知识库语料）
└── docker-compose.yml     # 中间件编排
```
