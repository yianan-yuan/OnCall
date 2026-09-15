package org.example.service;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册，所以设置为 optional,真实环境通过mcp配置注入
    private QueryLogsTools queryLogsTools;

    @Autowired
    private ToolCallbackProvider tools;

    @Value("${deepseek.api-key}")
    private String deepSeekApiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String deepSeekBaseUrl;

    /** 对话使用的大语言模型名称，取自 application.yml 的 {@code chat.model}（指定用 Flash） */
    @Value("${chat.model:deepseek-flash}")
    private String chatModelName;

    /**
     * 是否把 MCP（腾讯云日志等）工具交给 Agent 使用
     * <p>
     * 设为 {@code false} 时 MCP 客户端照常连接，只是不把它的工具交给模型，日志查询改由本地工具承担。
     */
    @Value("${chat.use-mcp-tools:true}")
    private boolean useMcpTools;

    /** 启动时把实际生效的模型打出来，便于确认 chat.model 配置有没有生效 */
    @PostConstruct
    public void logChatModel() {
        logger.info("对话模型已就绪: {}（base-url: {}）", chatModelName, deepSeekBaseUrl);
    }

    /**
     * 创建 DeepSeek API 实例
     * <p>
     * 走 OpenAI 兼容协议，base-url 可在配置里替换成任意兼容端点。
     */
    public DeepSeekApi createDeepSeekApi() {
        return DeepSeekApi.builder()
                .apiKey(deepSeekApiKey)
                .baseUrl(deepSeekBaseUrl)
                .build();
    }

    /**
     * 创建 ChatModel
     * @param deepSeekApi DeepSeek API 实例
     * @param temperature 控制随机性 (0.0-1.0)
     * @param maxToken 最大输出长度
     * @param topP 核采样参数
     */
    public DeepSeekChatModel createChatModel(DeepSeekApi deepSeekApi, double temperature, int maxToken, double topP) {
        return DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model(chatModelName)
                        .temperature(temperature)
                        .maxTokens(maxToken)
                        .topP(topP)
                        .build())
                .build();
    }

    /**
     * 创建标准对话 ChatModel（默认参数）
     */
    public DeepSeekChatModel createStandardChatModel() {
        return createChatModel(createDeepSeekApi(), 0.7, 2000, 0.9);
    }

    /**
     * 创建 AIOps 排查专用的 ChatModel
     * <p>
     * 与对话模型的区别：温度更低（0.3）、输出上限更高（8000）；手动触发与告警自动触发的诊断共用这一份配置。
     *
     * @return 配置好的 ChatModel
     */
    public DeepSeekChatModel createAiOpsChatModel() {
        return createChatModel(createDeepSeekApi(), 0.3, 8000, 0.9);
    }

    /**
     * 构建基础系统提示词（不含历史对话）
     * <p>
     * 历史对话与记忆摘要由 {@code ChatMemoryService} 拼装后追加，这里只描述助手身份与可用工具。
     *
     * @return 基础系统提示词
     */
    public String buildBaseSystemPrompt() {
        StringBuilder systemPromptBuilder = new StringBuilder();

        // 基础系统提示
        systemPromptBuilder.append("你是小智，一个专业的智能运维助手，可以获取当前时间、查询天气信息、搜索内部文档知识库，以及查询 Prometheus 告警信息。\n");
        systemPromptBuilder.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
        systemPromptBuilder.append("当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。\n");
        systemPromptBuilder.append("当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。\n");
        systemPromptBuilder.append("当用户需要查询腾讯云日志时，请调用腾讯云mcp服务查询,默认查询地域ap-guangzhou,查询时间范围为近一个月。\n");

        return systemPromptBuilder.toString();
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    public Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }

    /**
     * 获取工具回调列表（MCP 服务提供的工具）
     * 受 {@code chat.use-mcp-tools} 控制，关闭时返回空数组
     *
     * @return 工具回调数组，可能为空
     */
    public ToolCallback[] getToolCallbacks() {
        if (!useMcpTools) {
            return new ToolCallback[0];
        }
        return tools.getToolCallbacks();
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        if (!useMcpTools) {
            logger.info("已按配置关闭 MCP 工具（chat.use-mcp-tools=false），"
                    + "本次只使用本地工具：时间查询 / 知识库检索 / Prometheus 告警查询 / 日志查询(本地)");
            return;
        }
        ToolCallback[] toolCallbacks = tools.getToolCallbacks();
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(ChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .tools(getToolCallbacks())
                .build();
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        var response = agent.call(question);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }
}
