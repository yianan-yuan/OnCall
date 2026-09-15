package org.example.controller;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.memory.ChatContextLimitException;
import org.example.memory.ChatMemoryService;
import org.example.memory.ChatMessage;
import org.example.memory.ChatSessionState;
import org.example.memory.PreparedChatContext;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 * <p>
 * 会话历史交由 {@link ChatMemoryService} 统一管理（Redis 或内存存储 + 滑动窗口 + 增量摘要），
 * 进程重启不丢会话、多实例可共享，长对话也不会撑爆模型上下文窗口。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    /** AIOps 排查留在会话里的用户侧消息，便于从历史记录看出这一轮是谁触发的 */
    private static final String AIOPS_SESSION_PROMPT = "触发 AI Ops 智能运维排查";

    @Autowired
    private AiOpsService aiOpsService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatMemoryService chatMemoryService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 普通对话接口（支持工具调用）
     * 与 /chat_stream 逻辑一致，但直接返回完整结果而非流式输出
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        try {
            logger.info("收到对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

            // 参数校验
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("问题内容不能为空")));
            }

            // 创建 ChatModel（DeepSeek）
            ChatModel chatModel = chatService.createStandardChatModel();

            // 记录可用工具
            chatService.logAvailableTools();

            // 准备上下文：按上下文预算决定是否先做一次增量压缩，并拿到拼装好的系统提示词
            PreparedChatContext prepared = chatMemoryService.prepare(
                    request.getId(), request.getQuestion(), chatService.buildBaseSystemPrompt());
            String sessionId = prepared.getSession().getSessionId();

            logger.info("开始 ReactAgent 对话（支持自动调用工具）, 上下文占用 {}%", prepared.getUsagePercent());

            // 创建 ReactAgent 并执行对话
            ReactAgent agent = chatService.createReactAgent(chatModel, prepared.getSystemPrompt());
            String fullAnswer = chatService.executeChat(agent, request.getQuestion());

            // 把本轮问答追加进会话（原文永久保留，压缩只推进水位线）
            chatMemoryService.appendTurn(sessionId, request.getQuestion(), fullAnswer);

            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer)));

        } catch (ChatContextLimitException e) {
            // 上下文超限：明确告知用户，而不是截断历史后给出不可信的答案
            logger.warn("上下文超限，拒绝本轮请求: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        } catch (Exception e) {
            logger.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            chatMemoryService.clear(request.getId());
            return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));

        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询日志，告警等）
     * 支持 session 管理，保留对话历史与上下文记忆
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        executor.execute(() -> {
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

                // 创建 ChatModel（DeepSeek）
                ChatModel chatModel = chatService.createStandardChatModel();

                // 记录可用工具
                chatService.logAvailableTools();

                // 准备上下文：压缩可能调用大模型，所以整体放在后台线程，避免阻塞接口返回 SSE 连接
                PreparedChatContext prepared;
                try {
                    prepared = chatMemoryService.prepare(
                            request.getId(), request.getQuestion(), chatService.buildBaseSystemPrompt());
                } catch (ChatContextLimitException e) {
                    logger.warn("上下文超限，拒绝本轮流式请求: {}", e.getMessage());
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error(e.getMessage()), MediaType.APPLICATION_JSON));
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                String sessionId = prepared.getSession().getSessionId();

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）, 上下文占用 {}%, 本轮已压缩: {}",
                        prepared.getUsagePercent(), prepared.isCompacted());

                // 创建 ReactAgent
                ReactAgent agent = chatService.createReactAgent(chatModel, prepared.getSystemPrompt());

                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();

                // 使用 agent.stream() 进行流式对话
                Flux<NodeOutput> stream = agent.stream(request.getQuestion());

                stream.subscribe(
                    output -> {
                        try {
                            // 检查是否为 StreamingOutput 类型
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();

                                // 处理模型推理的流式输出
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    // 流式增量内容，逐步显示
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        fullAnswerBuilder.append(chunk);

                                        // 实时发送到前端
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));

                                        logger.info("发送流式内容: {}", chunk);
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    // 模型推理完成
                                    logger.info("模型输出完成");
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    // 工具调用完成
                                    logger.info("工具调用完成: {}", output.node());
                                } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                    // Hook 执行完成
                                    logger.debug("Hook 执行完成: {}", output.node());
                                }
                            }
                        } catch (IOException e) {
                            logger.error("发送流式消息失败", e);
                            throw new RuntimeException(e);
                        }
                    },
                    error -> {
                        // 错误处理
                        logger.error("ReactAgent 流式对话失败", error);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.error(error.getMessage()), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.completeWithError(error);
                    },
                    () -> {
                        // 完成处理
                        try {
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}",
                                    sessionId, fullAnswer.length());

                            // 把本轮问答追加进会话（原文永久保留）
                            chatMemoryService.appendTurn(sessionId, request.getQuestion(), fullAnswer);

                            // 发送完成标记
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        }
                    }
                );

            } catch (Exception e) {
                logger.error("ReactAgent 对话初始化失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.error(e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * AI 智能运维接口（SSE 流式模式）：自动分析告警并生成运维报告
     * <p>
     * 与告警自动触发（Alertmanager Webhook）共用同一套多 Agent 编排与模型配置，
     * 区别只是这里由用户手动触发，任务提示词使用默认版本。
     */
    /**
     * 触发一次 AIOps 多 Agent 排查，报告以 SSE 流式返回
     * <p>
     * 传了 sessionId 就把这一轮（触发指令 + 最终报告）追加进该会话，刷新后能从"近期对话"点回来继续追问；
     * 不传则只流式输出、不落会话。
     *
     * @param sessionId 会话 ID，可为空
     * @return SSE 事件流
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps(@RequestParam(value = "sessionId", required = false) String sessionId) {
        SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时（告警分析可能较慢）

        executor.execute(() -> {
            try {
                logger.info("收到 AI 智能运维请求 - 启动多 Agent 协作流程（流式）");

                ChatModel chatModel = chatService.createAiOpsChatModel();
                // 走 ChatService 取工具：这样 chat.use-mcp-tools=false 时能统一屏蔽 MCP 工具
                ToolCallback[] toolCallbacks = chatService.getToolCallbacks();

                emitter.send(SseEmitter.event().name("message").data(SseMessage.content("正在读取告警并拆解任务...\n")));

                // 边执行边推送：模型增量文本走 content，模型轮次与工具调用走 detail（前端"查看详细步骤"）
                AtomicReference<OverAllState> lastState = new AtomicReference<>();
                AiOpsStepCollector collector = new AiOpsStepCollector();
                aiOpsService.streamAiOpsAnalysis(chatModel, toolCallbacks, null)
                        .doOnNext(output -> handleAiOpsOutput(output, collector, lastState, emitter))
                        .blockLast();

                OverAllState state = lastState.get();
                if (state == null) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("多 Agent 编排未获取到有效结果"), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                logger.info("AI Ops 编排完成，开始提取最终报告...");

                // 提取最终报告
                Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);

                // 输出最终报告
                if (finalReportOptional.isPresent()) {
                    String finalReportText = finalReportOptional.get();
                    logger.info("提取到 Planner 最终报告，长度: {}", finalReportText.length());

                    // 先落会话再流式输出：客户端中途断开也不影响报告已经存下来
                    appendAiOpsTurn(sessionId, finalReportText);

                    // 发送分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));

                    // 发送完整的告警分析报告
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("📋 **告警分析报告**\n\n"), MediaType.APPLICATION_JSON));

                    int chunkSize = 50;
                    for (int i = 0; i < finalReportText.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, finalReportText.length());
                        String chunk = finalReportText.substring(i, end);

                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                    }

                    // 发送结束分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));

                    logger.info("最终报告已完整输出");
                } else {
                    logger.warn("未能提取到 Planner 最终报告");
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("⚠️ 多 Agent 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("AI Ops 多 Agent 编排完成");

            } catch (Exception e) {
                logger.error("AI Ops 多 Agent 协作失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("AI Ops 流程失败: " + e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            Optional<ChatSessionState> sessionOptional = chatMemoryService.findSession(sessionId);
            if (sessionOptional.isPresent()) {
                ChatSessionState session = sessionOptional.get();
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(session.getSessionId());
                response.setMessagePairCount(session.getMessages().size() / 2);
                response.setCreateTime(session.getCreatedAt());
                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 获取会话的上下文记忆状态（供前端展示模式、token 占用、已压缩条数、摘要与存储介质）
     * <p>
     * 传入基础系统提示词让服务端重算一次占用，保证前端看到的占用率跟着最新消息走。
     *
     * @param sessionId 会话 ID
     * @return 记忆状态
     */
    @GetMapping("/chat/session/{sessionId}/memory")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSessionMemory(@PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    chatMemoryService.memoryPayload(sessionId, chatService.buildBaseSystemPrompt())));
        } catch (Exception e) {
            logger.error("获取上下文记忆状态失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 列出最近的会话（前端"近期对话"从后端加载）
     * <p>
     * 会话存在 Redis 里，因此刷新页面、换浏览器都能看到历史，不再依赖浏览器的 localStorage。
     *
     * @param limit 最多返回多少条，默认 50
     * @return 会话摘要列表（会话 ID、标题、消息数、时间）
     */
    @GetMapping("/chat/sessions")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listSessions(
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        try {
            return ResponseEntity.ok(ApiResponse.success(chatMemoryService.listSessionSummaries(limit)));
        } catch (Exception e) {
            logger.error("获取会话列表失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 读取某个会话的消息列表（刷新页面后回填对话内容）
     *
     * @param sessionId 会话 ID
     * @return 消息列表，每项含 role/type/content
     */
    @GetMapping("/chat/session/{sessionId}/messages")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> listSessionMessages(@PathVariable String sessionId) {
        try {
            List<Map<String, String>> payload = new ArrayList<>();
            for (ChatMessage message : chatMemoryService.listMessages(sessionId)) {
                if (message == null) {
                    continue;
                }
                String role = message.isAssistant() ? "assistant" : "user";
                Map<String, String> item = new LinkedHashMap<>();
                item.put("role", role);
                item.put("type", role);   // 前端渲染用的是 type 字段，这里一并给出
                item.put("content", message.getContent());
                payload.add(item);
            }
            return ResponseEntity.ok(ApiResponse.success(payload));
        } catch (Exception e) {
            logger.error("获取会话消息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 手动压缩会话上下文
     * 对应 manual 模式：把滑动窗口之外的对话压成摘要，立刻降低上下文占用
     *
     * @param sessionId 会话 ID
     * @return 压缩后的记忆状态
     */
    @PostMapping("/chat/session/{sessionId}/compact")
    public ResponseEntity<ApiResponse<Map<String, Object>>> compactSession(@PathVariable String sessionId) {
        try {
            logger.info("收到手动压缩上下文请求 - SessionId: {}", sessionId);
            chatMemoryService.compactNow(sessionId, chatService.buildBaseSystemPrompt());
            return ResponseEntity.ok(ApiResponse.success(
                    chatMemoryService.memoryPayload(sessionId, chatService.buildBaseSystemPrompt())));
        } catch (Exception e) {
            logger.error("手动压缩上下文失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 把一轮 AIOps 报告追加进会话
     *
     * @param sessionId   会话 ID，为空时不做任何事
     * @param finalReport 最终报告正文
     */
    private void appendAiOpsTurn(String sessionId, String finalReport) {
        if (sessionId == null || sessionId.isBlank() || finalReport == null || finalReport.isBlank()) {
            return;
        }
        try {
            chatMemoryService.appendTurn(sessionId, AIOPS_SESSION_PROMPT, finalReport);
            logger.info("AIOps 报告已写入会话: sessionId={}, 报告长度={}", sessionId, finalReport.length());
        } catch (Exception e) {
            // 落库失败不影响已经流式输出给用户的报告
            logger.warn("AIOps 报告写入会话失败: sessionId={}, {}", sessionId, e.getMessage());
        }
    }

    /**
     * 处理一个 AIOps 图节点输出：增量文本转发给前端，模型轮次与工具调用记成步骤明细
     * <p>
     * 框架只保证发增量文本与工具完成两类事件，所以"一轮模型输出"以节点切换或工具调用为界。
     *
     * @param output    节点输出
     * @param collector 步骤收集状态（每次请求一个）
     * @param lastState 记录最后一个状态，流结束后从里面取最终报告
     * @param emitter   SSE 通道
     */
    private void handleAiOpsOutput(NodeOutput output,
                                   AiOpsStepCollector collector,
                                   AtomicReference<OverAllState> lastState,
                                   SseEmitter emitter) {
        lastState.set(output.state());

        if (!(output instanceof StreamingOutput<?> streamingOutput)) {
            return;
        }

        OutputType type = streamingOutput.getOutputType();
        String node = output.node() == null ? "agent" : output.node();

        try {
            if (type == OutputType.AGENT_MODEL_STREAMING) {
                String chunk = streamingOutput.chunk();
                if (chunk == null || chunk.isEmpty()) {
                    return;
                }
                // 换了节点说明上一轮模型输出已经结束，先把那一轮记成一条步骤
                if (!node.equals(collector.currentNode)) {
                    flushModelStep(collector, emitter);
                    collector.currentNode = node;
                }
                collector.modelBuffer.append(chunk);
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
            } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                flushModelStep(collector, emitter);
                sendToolSteps(emitter, node, streamingOutput);
            } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                flushModelStep(collector, emitter);
            }
        } catch (IOException e) {
            throw new RuntimeException("推送 AIOps 流式结果失败", e);
        }
    }

    /**
     * 把累积的一轮模型输出记成一条步骤明细
     *
     * @param collector 步骤收集状态
     * @param emitter   SSE 通道
     * @throws IOException 推送失败
     */
    private void flushModelStep(AiOpsStepCollector collector, SseEmitter emitter) throws IOException {
        String text = collector.modelBuffer.toString().trim();
        collector.modelBuffer.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        sendAiOpsStep(emitter, "assistant [" + collector.currentNode + "]: " + truncate(text, 400));
    }

    /**
     * 把一次工具调用记成步骤明细（工具名 + 返回内容）
     *
     * @param emitter SSE 通道
     * @param node    节点名
     * @param output  节点输出
     * @throws IOException 推送失败
     */
    private void sendToolSteps(SseEmitter emitter, String node, StreamingOutput<?> output) throws IOException {
        // 工具返回内容在 ToolResponseMessage 里；拿不到时退回节点原始数据，保证步骤不丢
        if (output.message() instanceof ToolResponseMessage toolResponseMessage) {
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                sendAiOpsStep(emitter, "tool " + response.name() + ": " + truncate(response.responseData(), 600));
            }
            return;
        }
        sendAiOpsStep(emitter, "tool [" + node + "]: " + truncate(String.valueOf(output.getOriginData()), 400));
    }

    /**
     * 推送一条步骤明细
     *
     * @param emitter SSE 通道
     * @param step    步骤描述
     * @throws IOException 推送失败
     */
    private void sendAiOpsStep(SseEmitter emitter, String step) throws IOException {
        emitter.send(SseEmitter.event().name("message")
                .data(SseMessage.detail(step), MediaType.APPLICATION_JSON));
    }

    /**
     * 截断过长的步骤内容，避免单条明细把面板刷爆
     *
     * @param text      原始文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= maxLength ? singleLine : singleLine.substring(0, maxLength) + "…";
    }

    /**
     * AIOps 流式过程中的步骤收集状态
     * <p>
     * 模型输出逐字到达，要攒够一轮才记成一条步骤，所以状态跟着单次请求走。
     */
    private static class AiOpsStepCollector {

        /** 当前轮次的增量文本 */
        private final StringBuilder modelBuffer = new StringBuilder();

        /** 当前正在输出的节点名 */
        private String currentNode = "";
    }

    // ==================== 内部类 ====================

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;

        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;

    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private String createTime;
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    /**
     * 统一 SSE 流式消息格式
     * 适用于所有 SSE 流式返回模式的对话接口
     */
    @Setter
    @Getter
    public static class SseMessage {
        private String type;  // content: 内容块, detail: 执行步骤明细, error: 错误, done: 完成
        private String data;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        /** 执行步骤明细：与 content 分开，避免过程信息混进最终正文 */
        public static SseMessage detail(String data) {
            SseMessage message = new SseMessage();
            message.setType("detail");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }


    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }

    }
}
