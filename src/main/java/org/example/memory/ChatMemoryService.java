package org.example.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话上下文记忆服务
 * <p>
 * 分层记忆：滑动窗口保留最近 N 轮原文，更早的对话压缩成摘要，压缩水位线记录已摘要到哪一条，
 * 每次只把新增部分并入摘要，不重算历史。未摘要对话超过"窗口 + 批量阈值"，或上下文占用达到
 * 自动压缩阈值时触发一次压缩；压缩后仍超过硬上限则拒绝本轮请求。压缩只推进水位线，不删除原文。
 */
@Service
public class ChatMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(ChatMemoryService.class);

    /** 摘要注入提示词时的引导语（TokenEstimator 中使用的同一句，改动需同步） */
    static final String MEMORY_INSTRUCTION_PREFIX = TokenEstimator.MEMORY_INSTRUCTION_PREFIX;

    private final ChatMemoryStore chatMemoryStore;

    private final ChatMemoryProperties properties;

    private final TokenEstimator tokenEstimator;

    private final MemorySummarizer memorySummarizer;

    /** 按会话维度加锁，防止同一会话的并发请求重复压缩或相互覆盖 */
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public ChatMemoryService(ChatMemoryStore chatMemoryStore,
                             ChatMemoryProperties properties,
                             TokenEstimator tokenEstimator,
                             MemorySummarizer memorySummarizer) {
        this.chatMemoryStore = chatMemoryStore;
        this.properties = properties;
        this.tokenEstimator = tokenEstimator;
        this.memorySummarizer = memorySummarizer;

        logger.info("✅ 上下文记忆服务初始化完成, 窗口: {} 轮, 窗口预算: {} tokens, "
                        + "自动压缩: {}%, 硬上限: {}%, 模式: {}, 存储: {}",
                properties.getWindowTurns(),
                properties.getContextWindowTokens(),
                properties.getAutoCompactPercent(),
                properties.getHardLimitPercent(),
                properties.getMode(),
                chatMemoryStore.describe());
    }

    /**
     * 准备本轮对话的上下文（必要时执行一次增量压缩）
     *
     * @param sessionId        会话 ID，为空时自动生成
     * @param userQuestion     本轮用户问题
     * @param baseSystemPrompt 基础系统提示词
     * @return 准备好的上下文（含拼装后的系统提示词）
     * @throws ChatContextLimitException 压缩后仍超出硬上限时抛出
     */
    public PreparedChatContext prepare(String sessionId, String userQuestion, String baseSystemPrompt) {
        String normalizedSessionId = normalizeSessionId(sessionId);

        synchronized (lockFor(normalizedSessionId)) {
            ChatSessionState session = loadOrCreate(normalizedSessionId);

            // 候选上下文 = 未压缩历史 + 本轮问题（压缩前的真实占用）
            List<ChatMessage> candidateMessages = new ArrayList<>(session.uncompressedMessages());
            candidateMessages.add(ChatMessage.user(userQuestion));

            int candidateTokens = tokenEstimator.estimateContextTokens(
                    baseSystemPrompt, session.getMemorySummary(), candidateMessages);

            boolean compacted = false;
            boolean compactionFailed = false;

            if (shouldCompact(session, candidateTokens)) {
                try {
                    compacted = compact(session, baseSystemPrompt);
                } catch (Exception e) {
                    // 压缩失败不阻塞本轮对话：如实记录，下一轮再尝试
                    compactionFailed = true;
                    logger.error("生成记忆摘要失败，本轮跳过压缩: sessionId={}", normalizedSessionId, e);
                }

                // 压缩后只剩本轮问题需要计入
                candidateTokens = tokenEstimator.estimateContextTokens(
                        baseSystemPrompt, session.getMemorySummary(), List.of(ChatMessage.user(userQuestion)));
            }

            double usagePercent = usagePercent(candidateTokens);
            if (usagePercent >= properties.getHardLimitPercent()) {
                throw new ChatContextLimitException(candidateTokens, usagePercent);
            }

            session.setContextTokens(candidateTokens);
            session.setUpdatedAt(Instant.now().toString());
            chatMemoryStore.save(session);

            String systemPrompt = buildSystemPrompt(baseSystemPrompt, session);

            logger.info("上下文准备完成: sessionId={}, tokens≈{}, 占用={}%, 已压缩={}, 水位线={}/{}",
                    normalizedSessionId, candidateTokens, String.format("%.1f", usagePercent),
                    compacted, session.getCompactedMessageCount(), session.getMessages().size());

            return new PreparedChatContext(session, systemPrompt, candidateTokens, usagePercent,
                    compacted, compactionFailed);
        }
    }

    /**
     * 追加一轮完整对话（用户问题 + 助手回答）
     * <p>
     * 只追加不删除：压缩靠水位线表达，历史原文始终保留。
     *
     * @param sessionId        会话 ID
     * @param userQuestion     用户问题
     * @param assistantAnswer  助手回答
     */
    public void appendTurn(String sessionId, String userQuestion, String assistantAnswer) {
        String normalizedSessionId = normalizeSessionId(sessionId);

        synchronized (lockFor(normalizedSessionId)) {
            ChatSessionState session = loadOrCreate(normalizedSessionId);

            session.getMessages().add(ChatMessage.user(userQuestion));
            session.getMessages().add(ChatMessage.assistant(assistantAnswer));
            session.setUpdatedAt(Instant.now().toString());

            chatMemoryStore.save(session);

            logger.debug("会话已追加一轮对话: sessionId={}, 总消息数={}, 水位线={}",
                    normalizedSessionId, session.getMessages().size(), session.getCompactedMessageCount());
        }
    }

    /**
     * 手动压缩当前会话（manual 模式或用户主动点击时使用）
     *
     * @param sessionId        会话 ID
     * @param baseSystemPrompt 基础系统提示词
     * @return 压缩后的会话状态
     */
    public ChatSessionState compactNow(String sessionId, String baseSystemPrompt) {
        String normalizedSessionId = normalizeSessionId(sessionId);

        synchronized (lockFor(normalizedSessionId)) {
            ChatSessionState session = loadOrCreate(normalizedSessionId);
            compact(session, baseSystemPrompt);
            chatMemoryStore.save(session);
            return session;
        }
    }

    /**
     * 读取会话状态
     *
     * @param sessionId 会话 ID
     * @return 会话状态
     */
    public Optional<ChatSessionState> findSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return chatMemoryStore.load(sessionId);
    }

    /**
     * 清空会话（删除全部历史与摘要）
     *
     * @param sessionId 会话 ID
     */
    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        chatMemoryStore.delete(sessionId);
        logger.info("会话已清空: sessionId={}", sessionId);
    }

    /**
     * 组装给前端展示的记忆状态
     *
     * @param sessionId 会话 ID
     * @return 状态字段映射
     */
    public Map<String, Object> memoryPayload(String sessionId) {
        return memoryPayload(sessionId, null);
    }

    /**
     * 组装给前端展示的记忆状态
     * <p>
     * 传入基础系统提示词时会按当前消息重算占用：{@code contextTokens} 只在提问前与压缩后更新，
     * 答完并不重算，前端直接读会慢一轮。重算只统计本地字符数，不调用模型。
     *
     * @param sessionId        会话 ID
     * @param baseSystemPrompt 基础系统提示词；为 null 时沿用会话里已存的占用值
     * @return 状态字段映射
     */
    public Map<String, Object> memoryPayload(String sessionId, String baseSystemPrompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Optional<ChatSessionState> sessionOptional = findSession(sessionId);

        if (sessionOptional.isEmpty()) {
            payload.put("sessionId", sessionId);
            payload.put("exists", false);
            return payload;
        }

        ChatSessionState session = sessionOptional.get();
        payload.put("sessionId", session.getSessionId());
        payload.put("exists", true);
        payload.put("mode", session.getMemoryMode());
        payload.put("messageCount", session.getMessages().size());
        payload.put("compactedMessageCount", session.getCompactedMessageCount());
        int contextTokens = baseSystemPrompt == null
                ? session.getContextTokens()
                : estimateContextTokens(baseSystemPrompt, session);
        payload.put("contextTokens", contextTokens);
        payload.put("contextWindowTokens", properties.getContextWindowTokens());
        payload.put("contextUsagePercent", usagePercent(contextTokens));
        payload.put("memorySummary", session.getMemorySummary());
        payload.put("lastCompactedAt", session.getLastCompactedAt());
        payload.put("createdAt", session.getCreatedAt());
        payload.put("updatedAt", session.getUpdatedAt());
        payload.put("store", chatMemoryStore.describe());
        return payload;
    }

    /**
     * 估算指定系统提示词下的上下文占用（用于状态展示）
     *
     * @param baseSystemPrompt 基础系统提示词
     * @param session          会话状态
     * @return 估算的 token 数
     */
    public int estimateContextTokens(String baseSystemPrompt, ChatSessionState session) {
        if (session == null) {
            return 0;
        }
        return tokenEstimator.estimateContextTokens(
                baseSystemPrompt, session.getMemorySummary(), session.uncompressedMessages());
    }

    /**
     * 列出最近的会话摘要
     * <p>
     * 供前端"近期对话"列表使用：让历史会话跟着后端存储走，
     * 而不是只存在浏览器的 localStorage 里（换浏览器/清缓存就丢）。
     *
     * @param limit 最多返回多少条
     * @return 会话摘要列表，按最近更新倒序
     */
    public List<Map<String, Object>> listSessionSummaries(int limit) {
        List<Map<String, Object>> summaries = new ArrayList<>();

        for (ChatSessionState session : chatMemoryStore.listSessions(limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", session.getSessionId());
            item.put("title", resolveSessionTitle(session));
            item.put("messageCount", session.getMessages() == null ? 0 : session.getMessages().size());
            item.put("createdAt", session.getCreatedAt());
            item.put("updatedAt", session.getUpdatedAt());
            summaries.add(item);
        }

        return summaries;
    }

    /**
     * 读取某个会话的全部消息
     *
     * @param sessionId 会话 ID
     * @return 消息列表；会话不存在时返回空列表
     */
    public List<ChatMessage> listMessages(String sessionId) {
        return findSession(sessionId)
                .map(session -> session.getMessages() == null
                        ? new ArrayList<ChatMessage>()
                        : new ArrayList<>(session.getMessages()))
                .orElseGet(ArrayList::new);
    }

    /**
     * 生成会话标题：取第一条用户消息，压缩空白并截断到 30 字
     *
     * @param session 会话状态
     * @return 标题文本
     */
    private String resolveSessionTitle(ChatSessionState session) {
        if (session.getMessages() == null) {
            return "新对话";
        }
        for (ChatMessage message : session.getMessages()) {
            if (message != null && message.isUser()
                    && message.getContent() != null && !message.getContent().isBlank()) {
                String content = message.getContent().strip().replaceAll("\\s+", " ");
                return content.length() > 30 ? content.substring(0, 30) + "..." : content;
            }
        }
        return "新对话";
    }

    // ==================== 内部实现 ====================

    /**
     * 判断本轮是否需要压缩
     *
     * @param session        会话状态
     * @param candidateTokens 压缩前的上下文 token 估算
     * @return true 表示需要压缩
     */
    private boolean shouldCompact(ChatSessionState session, int candidateTokens) {
        if (!properties.isEnabled()) {
            return false;
        }

        String mode = session.getMemoryMode() == null ? properties.getMode() : session.getMemoryMode();
        if ("manual".equalsIgnoreCase(mode)) {
            // 手动模式：只有用户主动触发才压缩
            return false;
        }

        if (session.uncompressedMessages().isEmpty()) {
            return false;
        }

        boolean overTokenThreshold = usagePercent(candidateTokens) >= properties.getAutoCompactPercent();
        int turns = session.uncompressedCompletedTurns();
        int window = Math.max(properties.getWindowTurns(), 1);

        if ("every_30_turns".equalsIgnoreCase(mode)) {
            return turns >= Math.max(properties.getEveryNTurns(), 1) || overTokenThreshold;
        }

        // 默认模式：攒批触发 + token 兜底
        int backlogTurns = turns - window;
        boolean overBatchThreshold = backlogTurns >= Math.max(properties.getCompactBatchTurns(), 1);

        if (overBatchThreshold || overTokenThreshold) {
            logger.debug("触发上下文压缩: 积压轮数={}, 窗口={}, token 占用={}%",
                    backlogTurns, window, String.format("%.1f", usagePercent(candidateTokens)));
            return true;
        }
        return false;
    }

    /**
     * 执行一次增量压缩：把窗口之外、且尚未摘要的消息并入摘要
     *
     * @param session          会话状态
     * @param baseSystemPrompt 基础系统提示词（用于重新估算占用）
     * @return true 表示确实压缩了内容
     */
    private boolean compact(ChatSessionState session, String baseSystemPrompt) {
        int currentCount = Math.min(Math.max(session.getCompactedMessageCount(), 0), session.getMessages().size());
        int targetCount = session.targetCompactedMessageCount(properties.getWindowTurns());

        // 没有可压缩的内容（例如还没超出滑动窗口）时直接返回，避免无意义地调用模型
        if (targetCount <= currentCount) {
            return false;
        }

        List<ChatMessage> messagesToCompact = session.getMessages().subList(currentCount, targetCount);
        String transcript = buildTranscript(messagesToCompact);
        String prompt = buildCompactionPrompt(session.getMemorySummary(), transcript);

        String summary = callSummaryModel(prompt);

        session.setMemorySummary(summary);
        session.setCompactedMessageCount(targetCount);
        session.setLastCompactedAt(Instant.now().toString());
        session.setContextTokens(tokenEstimator.estimateContextTokens(
                baseSystemPrompt, summary, List.of()));

        logger.info("上下文压缩完成: 新并入 {} 条消息, 水位线推进到 {}, 摘要长度 {} 字",
                messagesToCompact.size(), targetCount, summary.length());

        return true;
    }

    /**
     * 把待压缩的消息拼成对话文稿
     *
     * @param messages 消息列表
     * @return 文稿文本
     */
    private String buildTranscript(List<ChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            String role = message.isAssistant() ? "assistant" : "user";
            builder.append(role).append(": ").append(message.getContent()).append('\n');
        }
        return builder.toString();
    }

    /**
     * 构建压缩提示词
     * <p>
     * 明确列出"必须保留什么、必须删除什么"，避免模型自由发挥导致关键约束丢失；
     * 把已有摘要一起传入，做增量合并，而不是重新总结全部历史。
     *
     * @param existingSummary 已有摘要，可为 null
     * @param transcript      本次新增对话文稿
     * @return 提示词
     */
    private String buildCompactionPrompt(String existingSummary, String transcript) {
        return "请将以下对话压缩为可供后续模型继续对话的中文记忆摘要。"
                + "保留用户目标、明确事实、偏好、决策、未完成事项、工具结果和引用来源；"
                + "删除寒暄与重复内容。只输出摘要正文，不超过 "
                + properties.getSummaryMaxChars() + " 个汉字。\n\n"
                + "已有摘要：\n"
                + (existingSummary == null || existingSummary.isBlank() ? "无" : existingSummary)
                + "\n\n新增对话：\n"
                + transcript;
    }

    /**
     * 调用摘要生成器并做长度兜底
     *
     * @param prompt 提示词
     * @return 摘要正文
     */
    private String callSummaryModel(String prompt) {
        String summary = memorySummarizer.summarize(prompt);

        if (summary == null || summary.isBlank()) {
            throw new IllegalStateException("模型返回了空的记忆摘要");
        }

        String trimmed = summary.trim();
        int maxChars = Math.max(properties.getSummaryMaxChars(), 100);
        if (trimmed.length() > maxChars) {
            // 兜底截断：防止个别模型无视字数要求把摘要写爆
            trimmed = trimmed.substring(0, maxChars);
        }
        return trimmed;
    }

    /**
     * 拼装最终系统提示词：基础提示 + 记忆摘要 + 未压缩的历史对话
     *
     * @param baseSystemPrompt 基础系统提示词
     * @param session          会话状态
     * @return 完整系统提示词
     */
    private String buildSystemPrompt(String baseSystemPrompt, ChatSessionState session) {
        StringBuilder builder = new StringBuilder(baseSystemPrompt == null ? "" : baseSystemPrompt);

        // 1. 记忆摘要（全局概览）
        if (session.getMemorySummary() != null && !session.getMemorySummary().isBlank()) {
            builder.append("\n\n").append(MEMORY_INSTRUCTION_PREFIX).append(session.getMemorySummary());
        }

        // 2. 未压缩的历史对话原文（含滑动窗口与尚未攒够批次的少量积压）
        List<ChatMessage> history = session.uncompressedMessages();
        if (!history.isEmpty()) {
            builder.append("\n\n--- 对话历史 ---\n");
            for (ChatMessage message : history) {
                if (message == null) {
                    continue;
                }
                builder.append(message.isAssistant() ? "助手: " : "用户: ")
                        .append(message.getContent())
                        .append('\n');
            }
            builder.append("--- 对话历史结束 ---\n");
        }

        builder.append("\n请基于以上对话历史与记忆摘要，回答用户的新问题。");
        return builder.toString();
    }

    /**
     * 加载会话，不存在则创建
     *
     * @param sessionId 会话 ID
     * @return 会话状态
     */
    private ChatSessionState loadOrCreate(String sessionId) {
        return chatMemoryStore.load(sessionId).orElseGet(() -> {
            ChatSessionState session = new ChatSessionState();
            session.setSessionId(sessionId);
            session.setMemoryMode(properties.getMode());
            String now = Instant.now().toString();
            session.setCreatedAt(now);
            session.setUpdatedAt(now);
            return session;
        });
    }

    /**
     * 规范化会话 ID：为空时生成一个
     *
     * @param sessionId 原始会话 ID
     * @return 非空会话 ID
     */
    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return sessionId.trim();
    }

    /**
     * 获取会话锁对象
     *
     * @param sessionId 会话 ID
     * @return 锁对象
     */
    private Object lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, key -> new Object());
    }

    /**
     * 计算占用百分比
     *
     * @param tokens token 数
     * @return 百分比（0~100）
     */
    private double usagePercent(int tokens) {
        int window = Math.max(properties.getContextWindowTokens(), 1);
        double percent = (double) tokens / window * 100d;
        return Math.min(Math.round(percent * 10d) / 10d, 100d);
    }
}
