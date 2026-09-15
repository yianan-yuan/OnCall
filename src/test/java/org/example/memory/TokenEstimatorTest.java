package org.example.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token 估算器单元测试
 * <p>
 * 估算值只用于"是否需要压缩"的判断，因此测试不追求精确值，
 * 而是锁死几条必须成立的性质：中文比英文更耗 token、空输入为 0、
 * 上下文估算随消息数量单调增长。
 */
class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    @DisplayName("空文本估算为 0")
    void shouldReturnZeroForEmptyText() {
        assertThat(estimator.estimate(null)).isZero();
        assertThat(estimator.estimate("")).isZero();
    }

    @Test
    @DisplayName("同样长度的中文比英文占用更多 token")
    void chineseShouldCostMoreTokensThanAscii() {
        String ascii = "abcdefghij";
        String chinese = "故障排查手册说明";

        int asciiTokens = estimator.estimate(ascii);
        int chineseTokens = estimator.estimate(chinese);

        assertThat(chineseTokens).isGreaterThan(asciiTokens);
    }

    @Test
    @DisplayName("上下文估算包含系统提示、摘要与消息开销")
    void shouldIncludeSystemPromptSummaryAndMessages() {
        String systemPrompt = "你是一个专业的智能助手。";
        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.user("支付服务超时怎么办"),
                ChatMessage.assistant("请先检查连接池配置。"));

        int withoutSummary = estimator.estimateContextTokens(systemPrompt, null, messages);
        int withSummary = estimator.estimateContextTokens(systemPrompt, "用户此前询问过支付服务超时。", messages);

        assertThat(withoutSummary).isPositive();
        // 摘要会额外占用 token
        assertThat(withSummary).isGreaterThan(withoutSummary);
        // 只算系统提示时一定比完整上下文小
        assertThat(estimator.estimateContextTokens(systemPrompt, null, Collections.emptyList()))
                .isLessThan(withoutSummary);
    }

    @Test
    @DisplayName("消息越多估算值越大（单调性）")
    void shouldGrowWithMoreMessages() {
        String systemPrompt = "系统提示";
        List<ChatMessage> oneTurn = Arrays.asList(
                ChatMessage.user("问题一"), ChatMessage.assistant("回答一"));
        List<ChatMessage> twoTurns = Arrays.asList(
                ChatMessage.user("问题一"), ChatMessage.assistant("回答一"),
                ChatMessage.user("问题二"), ChatMessage.assistant("回答二"));

        assertThat(estimator.estimateContextTokens(systemPrompt, null, twoTurns))
                .isGreaterThan(estimator.estimateContextTokens(systemPrompt, null, oneTurn));
    }
}
