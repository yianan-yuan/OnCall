package org.example.memory;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 上下文 token 估算器
 * <p>
 * 在调用大模型之前估算"这次请求会占用多少 token"，用来判断是否需要触发摘要压缩、是否已经逼近窗口上限。
 * <p>
 * 精确计数需要与模型完全一致的 tokenizer，引入它会给项目增加一个不轻的依赖，因此按字符类型估算并留安全余量。
 * 估算偏保守：宁可高估提前压缩，也不要低估撑爆窗口。需要更准时，可用模型响应里的 usage 字段校准后调整下面的系数。
 */
@Component
public class TokenEstimator {

    /** ASCII 字符与 token 的比例：约 4 个字符 1 个 token */
    private static final double ASCII_CHARS_PER_TOKEN = 4d;

    /** 非 ASCII 字符（中文等）每个字符的 token 数估算值 */
    private static final double NON_ASCII_TOKENS_PER_CHAR = 0.7d;

    /** 每条消息的固定开销（角色、分隔符等） */
    private static final int MESSAGE_OVERHEAD_TOKENS = 4;

    /**
     * 估算一段文本的 token 数
     *
     * @param text 文本，允许为空
     * @return 估算的 token 数
     */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int asciiCount = 0;
        int nonAsciiCount = 0;

        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) <= 0x7F) {
                asciiCount++;
            } else {
                nonAsciiCount++;
            }
        }

        double tokens = asciiCount / ASCII_CHARS_PER_TOKEN + nonAsciiCount * NON_ASCII_TOKENS_PER_CHAR;
        return (int) Math.ceil(tokens);
    }

    /**
     * 估算一次请求的上下文占用：系统提示词 + 记忆摘要（若存在）+ 全部待发送消息
     *
     * @param systemPrompt  系统提示词
     * @param memorySummary 历史摘要，可为 null
     * @param messages      待发送的消息列表，可为 null
     * @return 估算的总 token 数
     */
    public int estimateContextTokens(String systemPrompt, String memorySummary, List<ChatMessage> messages) {
        int total = estimate(systemPrompt);

        if (memorySummary != null && !memorySummary.isBlank()) {
            // 摘要会带上"以下是对此前对话的压缩记忆"这句引导语，一并计入
            total += estimate(MEMORY_INSTRUCTION_PREFIX + memorySummary);
        }

        if (messages != null) {
            for (ChatMessage message : messages) {
                if (message == null) {
                    continue;
                }
                total += estimate(message.getContent()) + MESSAGE_OVERHEAD_TOKENS;
            }
        }

        return total;
    }

    /** 摘要注入提示词时的引导语（与 ChatMemoryService 中保持一致） */
    static final String MEMORY_INSTRUCTION_PREFIX = "以下是此前对话的压缩记忆，请作为真实会话上下文继续回答：\n";
}
