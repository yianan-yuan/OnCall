package org.example.memory;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话状态（上下文记忆的载体）
 * <p>
 * {@code messages} 保存全部历史消息、永不删除：摘要只是"概览索引"，原文保留才能回查，
 * 也才能在摘要出错时重建。{@code memorySummary} 是更早对话的滚动摘要，
 * {@code compactedMessageCount} 是压缩水位线，保证每次只处理新增部分，不重算旧摘要。
 */
@Setter
@Getter
public class ChatSessionState {

    /** 会话 ID */
    private String sessionId;

    /** 全部历史消息（只追加，不删除） */
    private List<ChatMessage> messages = new ArrayList<>();

    /** 历史对话的压缩摘要，未压缩过时为 null */
    private String memorySummary;

    /** 压缩水位线：messages 中前多少条已经并入摘要 */
    private int compactedMessageCount;

    /** 最近一次估算的上下文 token 占用 */
    private int contextTokens;

    /** 当前使用的压缩模式 */
    private String memoryMode = "context_70_percent";

    /** 最近一次压缩时间（ISO8601），未压缩过时为 null */
    private String lastCompactedAt;

    /** 会话创建时间（ISO8601） */
    private String createdAt;

    /** 最近更新时间（ISO8601） */
    private String updatedAt;

    /**
     * 获取尚未并入摘要的消息（水位线之后的部分）
     *
     * @return 未压缩消息列表（副本）
     */
    public List<ChatMessage> uncompressedMessages() {
        List<ChatMessage> all = messages == null ? new ArrayList<>() : messages;
        int fromIndex = Math.min(Math.max(compactedMessageCount, 0), all.size());
        return new ArrayList<>(all.subList(fromIndex, all.size()));
    }

    /**
     * 统计未压缩消息中已完成的对话轮数（以助手回复条数计轮：只有答完才算一轮）
     *
     * @return 未压缩的完成轮数
     */
    public int uncompressedCompletedTurns() {
        int turns = 0;
        for (ChatMessage message : uncompressedMessages()) {
            if (message != null && message.isAssistant()) {
                turns++;
            }
        }
        return turns;
    }

    /**
     * 获取最近若干轮对话的原文（用于拼进提示词的滑动窗口）
     *
     * @param windowTurns 窗口轮数
     * @return 窗口内的消息列表（副本），顺序保持原始先后
     */
    public List<ChatMessage> recentWindowMessages(int windowTurns) {
        List<ChatMessage> all = messages == null ? new ArrayList<>() : messages;
        if (windowTurns <= 0) {
            return new ArrayList<>();
        }

        // 一轮按两条消息估算（用户 + 助手），从尾部往前截取
        int messageCount = Math.min(windowTurns * 2, all.size());
        return new ArrayList<>(all.subList(all.size() - messageCount, all.size()));
    }

    /**
     * 计算压缩水位线应该推进到的位置：保留最近 windowTurns 轮原文，其余并入摘要
     *
     * @param windowTurns 需要原样保留的轮数
     * @return 新的水位线位置
     */
    public int targetCompactedMessageCount(int windowTurns) {
        List<ChatMessage> all = messages == null ? new ArrayList<>() : messages;
        int keepMessages = Math.max(windowTurns * 2, 0);
        int target = all.size() - keepMessages;
        // 水位线只能前进，不能后退
        return Math.max(target, Math.min(Math.max(compactedMessageCount, 0), all.size()));
    }
}
