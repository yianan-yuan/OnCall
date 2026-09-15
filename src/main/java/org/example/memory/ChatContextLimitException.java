package org.example.memory;

/**
 * 上下文窗口超限异常
 * <p>
 * 触发条件：压缩之后上下文占用仍达到硬上限（默认 95%）。
 * <p>
 * 选择"拒绝本轮"而不是"截断历史继续跑"：静默截断会让模型在缺失上下文的情况下作答，
 * 用户却以为它是"记得"的，可能得到看似合理但完全错误的答案；明确拒绝是把问题暴露出来。
 */
public class ChatContextLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 当前估算的上下文 token 数 */
    private final int contextTokens;

    /** 当前占用百分比 */
    private final double usagePercent;

    public ChatContextLimitException(int contextTokens, double usagePercent) {
        super(String.format("本轮对话已超出上下文窗口上限（当前约 %d tokens，占用 %.1f%%），"
                + "请开启新会话或先手动压缩历史对话。", contextTokens, usagePercent));
        this.contextTokens = contextTokens;
        this.usagePercent = usagePercent;
    }

    public int getContextTokens() {
        return contextTokens;
    }

    public double getUsagePercent() {
        return usagePercent;
    }
}
