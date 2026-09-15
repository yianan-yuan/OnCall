package org.example.memory;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 对话上下文记忆配置
 * <p>
 * 对应 application.yml 中的 {@code chat.memory.*} 配置段。分层记忆由三部分组成：
 * 滑动窗口保留最近若干轮原文，更早的对话压缩成滚动摘要，压缩水位线控制只处理增量。
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "chat.memory")
public class ChatMemoryProperties {

    /** 是否启用记忆管理，关闭时退化为把全部历史原文塞进提示词，仅用于对比排查 */
    private boolean enabled = true;

    /** 存储介质：redis（默认，重启不丢）/ memory（仅本地调试） */
    private String store = "redis";

    /** 滑动窗口保留的最近轮数：这部分原文永远不参与压缩，保证短期对话零信息损失 */
    private int windowTurns = 5;

    /** 压缩批量阈值（轮数）：未压缩对话超过"窗口轮数 + 该阈值"才压缩一次，摊薄摘要调用成本 */
    private int compactBatchTurns = 5;

    /** 上下文窗口 token 预算，应与模型真实窗口一致，否则阈值判断会失真 */
    private int contextWindowTokens = 32768;

    /** 自动压缩阈值（百分比）：上下文占用达到该比例时触发一次增量压缩 */
    private double autoCompactPercent = 70d;

    /** 硬上限（百分比）：压缩后占用仍达到该比例时拒绝本轮请求，避免模型窗口被撑爆 */
    private double hardLimitPercent = 95d;

    /**
     * 压缩模式：every_30_turns 每 N 轮压缩一次、context_70_percent 按 token 占用自动压缩（默认）、manual 手动触发
     */
    private String mode = "context_70_percent";

    /** every_30_turns 模式下每多少轮压缩一次 */
    private int everyNTurns = 30;

    /** 摘要最大字数 */
    private int summaryMaxChars = 1200;

    /** Redis 键前缀 */
    private String redisKeyPrefix = "chat:session:";

    /** 会话在 Redis 中的过期时间（天） */
    private int redisTtlDays = 7;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public void setWindowTurns(int windowTurns) {
        this.windowTurns = windowTurns;
    }

    public void setCompactBatchTurns(int compactBatchTurns) {
        this.compactBatchTurns = compactBatchTurns;
    }

    public void setContextWindowTokens(int contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
    }

    public void setAutoCompactPercent(double autoCompactPercent) {
        this.autoCompactPercent = autoCompactPercent;
    }

    public void setHardLimitPercent(double hardLimitPercent) {
        this.hardLimitPercent = hardLimitPercent;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setEveryNTurns(int everyNTurns) {
        this.everyNTurns = everyNTurns;
    }

    public void setSummaryMaxChars(int summaryMaxChars) {
        this.summaryMaxChars = summaryMaxChars;
    }

    public void setRedisKeyPrefix(String redisKeyPrefix) {
        this.redisKeyPrefix = redisKeyPrefix;
    }

    public void setRedisTtlDays(int redisTtlDays) {
        this.redisTtlDays = redisTtlDays;
    }
}
