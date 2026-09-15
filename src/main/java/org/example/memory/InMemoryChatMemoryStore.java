package org.example.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存会话存储
 * <p>
 * 两个用途：Redis 不可用时的降级实现（保证本地没有 Redis 也能开发调试），以及单元测试中的替身。
 * <p>
 * 进程重启即丢失，多实例部署时各存各的，因此只适合本地调试。
 */
public class InMemoryChatMemoryStore implements ChatMemoryStore {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryChatMemoryStore.class);

    private final Map<String, ChatSessionState> sessions = new ConcurrentHashMap<>();

    @Override
    public Optional<ChatSessionState> load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        ChatSessionState stored = sessions.get(sessionId);
        // 返回副本，避免调用方修改对象时绕过 save() 直接改到"库"里的数据
        return Optional.ofNullable(stored).map(InMemoryChatMemoryStore::copy);
    }

    @Override
    public void save(ChatSessionState state) {
        if (state == null || state.getSessionId() == null || state.getSessionId().isBlank()) {
            logger.warn("会话状态缺少 sessionId，已忽略保存请求");
            return;
        }
        sessions.put(state.getSessionId(), copy(state));
    }

    @Override
    public void delete(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    @Override
    public String describe() {
        return "内存（重启会丢失）";
    }

    @Override
    public List<ChatSessionState> listSessions(int limit) {
        return sessions.values().stream()
                .map(InMemoryChatMemoryStore::copy)
                // 按最近更新时间倒序，最近聊过的排前面
                .sorted((left, right) -> compareUpdatedAtDesc(left.getUpdatedAt(), right.getUpdatedAt()))
                .limit(Math.max(limit, 1))
                .collect(Collectors.toList());
    }

    /**
     * 按 updatedAt 倒序比较（时间戳字符串为空时排到最后）
     *
     * @param left  左值
     * @param right 右值
     * @return 比较结果
     */
    static int compareUpdatedAtDesc(String left, String right) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        return safeRight.compareTo(safeLeft);
    }

    /**
     * 深拷贝会话状态
     *
     * @param source 原状态
     * @return 拷贝后的状态
     */
    private static ChatSessionState copy(ChatSessionState source) {
        ChatSessionState target = new ChatSessionState();
        target.setSessionId(source.getSessionId());
        target.setMessages(source.getMessages() == null
                ? new ArrayList<>() : new ArrayList<>(source.getMessages()));
        target.setMemorySummary(source.getMemorySummary());
        target.setCompactedMessageCount(source.getCompactedMessageCount());
        target.setContextTokens(source.getContextTokens());
        target.setMemoryMode(source.getMemoryMode());
        target.setLastCompactedAt(source.getLastCompactedAt());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
        return target;
    }
}
