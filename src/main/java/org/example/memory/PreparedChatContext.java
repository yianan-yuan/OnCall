package org.example.memory;

import lombok.Getter;

/**
 * 本轮对话准备好的上下文
 * <p>
 * 由 {@link ChatMemoryService#prepare} 产出，交给控制器去创建 Agent 并调用大模型。
 */
@Getter
public class PreparedChatContext {

    /** 会话状态（已包含本轮之前的全部历史） */
    private final ChatSessionState session;

    /** 拼装好的系统提示词：基础提示 + 记忆摘要 + 滑动窗口内的历史对话 */
    private final String systemPrompt;

    /** 本轮估算的上下文 token 数 */
    private final int contextTokens;

    /** 上下文窗口占用百分比 */
    private final double usagePercent;

    /** 本轮是否执行了压缩 */
    private final boolean compacted;

    /** 压缩是否失败（失败时不阻塞本轮对话，但上下文不会被压缩） */
    private final boolean compactionFailed;

    public PreparedChatContext(ChatSessionState session,
                               String systemPrompt,
                               int contextTokens,
                               double usagePercent,
                               boolean compacted,
                               boolean compactionFailed) {
        this.session = session;
        this.systemPrompt = systemPrompt;
        this.contextTokens = contextTokens;
        this.usagePercent = usagePercent;
        this.compacted = compacted;
        this.compactionFailed = compactionFailed;
    }
}
