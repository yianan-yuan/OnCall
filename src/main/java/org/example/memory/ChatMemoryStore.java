package org.example.memory;

import java.util.Optional;

/**
 * 对话会话存储接口
 * <p>
 * 把"会话放在哪里"与"上下文怎么管理"解耦，Redis 实现与内存实现可以互换。
 */
public interface ChatMemoryStore {

    /**
     * 读取会话状态
     *
     * @param sessionId 会话 ID
     * @return 会话状态；不存在时返回空
     */
    Optional<ChatSessionState> load(String sessionId);

    /**
     * 保存会话状态（新增或覆盖）
     *
     * @param state 会话状态
     */
    void save(ChatSessionState state);

    void delete(String sessionId);

    String describe();

    /**
     * 列出最近的会话（供前端"近期对话"列表从后端加载，不再只依赖浏览器本地缓存）
     *
     * @param limit 最多返回多少条（按最近更新时间倒序）
     * @return 会话状态列表，按 updatedAt 倒序；查询失败时返回空列表而不是抛异常
     */
    java.util.List<ChatSessionState> listSessions(int limit);
}
