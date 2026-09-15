package org.example.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上下文记忆服务单元测试
 * <p>
 * 用内存存储 + 假的摘要生成器，把"何时压缩、压缩哪一段、压缩失败怎么办"这些
 * 关键行为固定下来，不依赖 Redis 与大模型，因此可以离线高速运行。
 */
class ChatMemoryServiceTest {

    private static final String SESSION_ID = "session-test";
    private static final String BASE_PROMPT = "你是一个专业的智能助手。";

    private InMemoryChatMemoryStore store;

    private ChatMemoryProperties properties;

    private FakeSummarizer summarizer;

    private ChatMemoryService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryChatMemoryStore();

        properties = new ChatMemoryProperties();
        properties.setEnabled(true);
        properties.setStore("memory");
        properties.setWindowTurns(5);
        properties.setCompactBatchTurns(5);
        properties.setContextWindowTokens(32768);
        properties.setAutoCompactPercent(70d);
        properties.setHardLimitPercent(95d);
        properties.setMode("context_70_percent");
        properties.setEveryNTurns(30);
        properties.setSummaryMaxChars(1200);

        summarizer = new FakeSummarizer();
        service = new ChatMemoryService(store, properties, new TokenEstimator(), summarizer);
    }

    @Test
    @DisplayName("第一轮对话不触发压缩")
    void shouldNotCompactOnFirstTurn() {
        PreparedChatContext context = service.prepare(SESSION_ID, "第一轮提问", BASE_PROMPT);

        assertThat(context.isCompacted()).isFalse();
        assertThat(summarizer.calls).isZero();
        assertThat(context.getSystemPrompt()).contains(BASE_PROMPT);
    }

    @Test
    @DisplayName("未摘要对话攒够批量阈值后触发一次压缩，水位线推进且保留最近 5 轮")
    void shouldCompactWhenBacklogReachesBatchThreshold() {
        appendTurns(10);

        PreparedChatContext context = service.prepare(SESSION_ID, "第11轮提问", BASE_PROMPT);

        assertThat(context.isCompacted()).isTrue();
        assertThat(summarizer.calls).isEqualTo(1);

        ChatSessionState session = context.getSession();
        // 20 条历史消息，保留最近 5 轮（10 条）→ 水位线推进到 10
        assertThat(session.getCompactedMessageCount()).isEqualTo(10);
        assertThat(session.getMemorySummary()).isNotBlank();

        // 原文一条都不能少
        assertThat(session.getMessages()).hasSize(20);
    }

    @Test
    @DisplayName("压缩后仍保留最近几轮原文，早期对话进入摘要")
    void shouldKeepRecentTurnsVerbatimAfterCompaction() {
        appendTurns(10);

        PreparedChatContext context = service.prepare(SESSION_ID, "第11轮提问", BASE_PROMPT);
        String prompt = context.getSystemPrompt();

        // 最近一轮原文仍在提示词中
        assertThat(prompt).contains("第10轮提问");
        assertThat(prompt).contains("第10轮回答");
        // 早期对话已经不在提示词里（被折叠进摘要）
        assertThat(prompt).doesNotContain("第1轮提问");
        // 摘要被注入提示词
        assertThat(prompt).contains("压缩记忆");
    }

    @Test
    @DisplayName("token 占用达到阈值时即使积压不多也会压缩")
    void shouldCompactWhenTokenUsageReachesThreshold() {
        // 把窗口预算调到很小，让 7 轮对话就超过 70%
        properties.setContextWindowTokens(150);
        appendTurns(7);

        PreparedChatContext context = service.prepare(SESSION_ID, "继续提问", BASE_PROMPT);

        assertThat(context.isCompacted()).isTrue();
        assertThat(summarizer.calls).isEqualTo(1);
        // 压缩后占用必须显著下降
        assertThat(context.getUsagePercent()).isLessThan(70d);
    }

    @Test
    @DisplayName("manual 模式不会自动压缩")
    void shouldNotAutoCompactInManualMode() {
        properties.setMode("manual");
        appendTurns(12);

        PreparedChatContext context = service.prepare(SESSION_ID, "继续提问", BASE_PROMPT);

        assertThat(context.isCompacted()).isFalse();
        assertThat(summarizer.calls).isZero();
    }

    @Test
    @DisplayName("every_30_turns 模式未到轮数且 token 未超限时不压缩")
    void shouldRespectEveryNTurnsMode() {
        properties.setMode("every_30_turns");
        appendTurns(12);

        PreparedChatContext context = service.prepare(SESSION_ID, "继续提问", BASE_PROMPT);

        assertThat(context.isCompacted()).isFalse();
        assertThat(summarizer.calls).isZero();
    }

    @Test
    @DisplayName("摘要生成失败时不阻塞本轮对话，只标记失败")
    void shouldNotBlockTurnWhenSummaryFails() {
        summarizer.fail = true;
        appendTurns(10);

        PreparedChatContext context = service.prepare(SESSION_ID, "第11轮提问", BASE_PROMPT);

        assertThat(context.isCompactionFailed()).isTrue();
        assertThat(context.isCompacted()).isFalse();
        // 本轮照常可以继续，历史原文仍在提示词里
        assertThat(context.getSystemPrompt()).contains("第1轮提问");
    }

    @Test
    @DisplayName("压缩后仍超出硬上限时拒绝本轮请求")
    void shouldRejectWhenHardLimitExceeded() {
        // 极小的窗口预算 + 超长问题：压缩后依然超限
        properties.setContextWindowTokens(50);
        String hugeQuestion = "超时".repeat(500);

        assertThatThrownBy(() -> service.prepare(SESSION_ID, hugeQuestion, BASE_PROMPT))
                .isInstanceOf(ChatContextLimitException.class)
                .hasMessageContaining("上下文窗口上限");
    }

    @Test
    @DisplayName("追加对话只增不删，压缩不会删除历史原文")
    void appendTurnShouldOnlyAppend() {
        appendTurns(3);
        assertThat(store.load(SESSION_ID).orElseThrow().getMessages()).hasSize(6);

        appendTurns(9);
        service.prepare(SESSION_ID, "继续提问", BASE_PROMPT);
        service.compactNow(SESSION_ID, BASE_PROMPT);

        ChatSessionState session = store.load(SESSION_ID).orElseThrow();
        assertThat(session.getMessages()).hasSize(24);
        assertThat(session.getCompactedMessageCount()).isPositive();
    }

    @Test
    @DisplayName("记忆状态可用于前端展示")
    void shouldExposeMemoryPayload() {
        appendTurns(2);

        Map<String, Object> payload = service.memoryPayload(SESSION_ID);

        assertThat(payload.get("exists")).isEqualTo(true);
        assertThat(payload.get("messageCount")).isEqualTo(4);
        assertThat(payload.get("mode")).isEqualTo("context_70_percent");
        assertThat(payload.get("contextWindowTokens")).isEqualTo(32768);
        assertThat(payload.get("store")).isEqualTo("内存（重启会丢失）");
    }

    @Test
    @DisplayName("清空会话后历史与摘要都不存在")
    void shouldClearSession() {
        appendTurns(2);
        service.clear(SESSION_ID);

        assertThat(service.findSession(SESSION_ID)).isEmpty();
    }

    @Test
    @DisplayName("传入系统提示词时按当前消息重算占用，不传则沿用提问前的旧值")
    void shouldRecalculateUsageWhenBasePromptProvided() {
        // prepare 时只统计到"提问"，回答落库后并不会重算，所以旧值天然偏小
        service.prepare(SESSION_ID, "第一轮提问", BASE_PROMPT);
        service.appendTurn(SESSION_ID, "第一轮提问", "第一轮回答");

        Map<String, Object> stored = service.memoryPayload(SESSION_ID);
        Map<String, Object> recalculated = service.memoryPayload(SESSION_ID, BASE_PROMPT);

        // 重算把刚追加的回答也算进去，占用只会更大
        // （百分比四舍五入到 1 位小数，小样本下可能看不出差别，这里只比 token 数）
        assertThat((Integer) recalculated.get("contextTokens"))
                .isGreaterThan((Integer) stored.get("contextTokens"));
        assertThat((Double) recalculated.get("contextUsagePercent")).isNotNull();
    }

    @Test
    @DisplayName("会话不存在时记忆状态标记 exists=false，且不带占用字段")
    void shouldReportMissingSession() {
        Map<String, Object> payload = service.memoryPayload("session-not-exist", BASE_PROMPT);

        assertThat(payload.get("exists")).isEqualTo(false);
        assertThat(payload).doesNotContainKey("contextUsagePercent");
    }

    /**
     * 追加若干轮对话
     *
     * @param turns 轮数
     */
    private void appendTurns(int turns) {
        for (int i = 1; i <= turns; i++) {
            service.appendTurn(SESSION_ID, "第" + i + "轮提问", "第" + i + "轮回答");
        }
    }

    /**
     * 假的摘要生成器：可控失败、可统计调用次数
     */
    private static class FakeSummarizer implements MemorySummarizer {

        private int calls;

        private boolean fail;

        private String lastPrompt;

        @Override
        public String summarize(String prompt) {
            this.calls++;
            this.lastPrompt = prompt;
            if (fail) {
                throw new IllegalStateException("模拟摘要失败");
            }
            return "记忆摘要：用户此前询问过支付服务超时排查与连接池配置。";
        }
    }
}
