package org.example.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话状态单元测试
 * <p>
 * 重点锁死"压缩水位线"的行为：
 * 它必须只前进不后退，且压缩目标位置必须保证最近若干轮原文被完整保留——
 * 这是"滑动窗口里的对话永远零信息损失"这一承诺的实现基础。
 */
class ChatSessionStateTest {

    /**
     * 构造一个有若干轮对话的会话
     *
     * @param turns 轮数
     * @return 会话状态
     */
    private ChatSessionState buildSession(int turns) {
        ChatSessionState session = new ChatSessionState();
        session.setSessionId("session-1");
        for (int i = 1; i <= turns; i++) {
            session.getMessages().add(ChatMessage.user("问题" + i));
            session.getMessages().add(ChatMessage.assistant("回答" + i));
        }
        return session;
    }

    @Test
    @DisplayName("未压缩消息从水位线之后开始")
    void shouldReturnMessagesAfterWatermark() {
        ChatSessionState session = buildSession(3);
        // 消息序列为 [问题1, 回答1, 问题2, 回答2, 问题3, 回答3]，水位线 2 表示前两条已并入摘要
        session.setCompactedMessageCount(2);

        List<ChatMessage> uncompressed = session.uncompressedMessages();

        assertThat(uncompressed).hasSize(4);
        assertThat(uncompressed.get(0).getContent()).isEqualTo("问题2");
        assertThat(uncompressed.get(3).getContent()).isEqualTo("回答3");
    }

    @Test
    @DisplayName("轮数按助手回复计数")
    void shouldCountCompletedTurnsByAssistantMessages() {
        ChatSessionState session = buildSession(4);

        assertThat(session.uncompressedCompletedTurns()).isEqualTo(4);

        // 只有用户提问、还没回答时不算完成一轮
        session.getMessages().add(ChatMessage.user("进行中的问题"));
        assertThat(session.uncompressedCompletedTurns()).isEqualTo(4);
    }

    @Test
    @DisplayName("压缩目标位置保留最近 N 轮原文")
    void shouldKeepRecentTurnsWhenComputingTargetWatermark() {
        ChatSessionState session = buildSession(10);

        // 保留最近 5 轮 → 20 - 10 = 10 条消息可以并入摘要
        assertThat(session.targetCompactedMessageCount(5)).isEqualTo(10);
    }

    @Test
    @DisplayName("水位线只能前进，不能后退")
    void watermarkShouldNeverMoveBackwards() {
        ChatSessionState session = buildSession(10);
        session.setCompactedMessageCount(14);

        // 计算出的目标位置（10）小于当前水位线（14）时，必须保持 14
        assertThat(session.targetCompactedMessageCount(5)).isEqualTo(14);
    }

    @Test
    @DisplayName("滑动窗口取最近若干轮消息且保持原顺序")
    void recentWindowShouldKeepLatestTurnsInOrder() {
        ChatSessionState session = buildSession(10);

        List<ChatMessage> window = session.recentWindowMessages(2);

        assertThat(window).hasSize(4);
        assertThat(window.get(0).getContent()).isEqualTo("问题9");
        assertThat(window.get(3).getContent()).isEqualTo("回答10");
    }

    @Test
    @DisplayName("消息总数不足窗口时返回全部消息")
    void recentWindowShouldReturnAllWhenFewerMessages() {
        ChatSessionState session = buildSession(1);

        assertThat(session.recentWindowMessages(5)).hasSize(2);
        assertThat(session.recentWindowMessages(0)).isEmpty();
    }

    @Test
    @DisplayName("水位线超出消息总数时不会越界")
    void shouldHandleWatermarkBeyondMessageCount() {
        ChatSessionState session = buildSession(2);
        session.setCompactedMessageCount(99);

        assertThat(session.uncompressedMessages()).isEmpty();
        assertThat(session.uncompressedCompletedTurns()).isZero();
        assertThat(session.targetCompactedMessageCount(5)).isEqualTo(4);
    }
}
