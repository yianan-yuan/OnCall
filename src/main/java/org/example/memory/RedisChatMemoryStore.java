package org.example.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Redis 会话存储
 * <p>
 * 一个会话一个键，键名 {@code chat:session:{sessionId}}，值是整份会话状态的 JSON。选择单键 JSON 而不是
 * Hash + List 组合，是因为整份写入是原子的，不会出现"消息写成功、摘要没更新"的中间状态；会话数据量有界。
 * <p>
 * 选 Redis 是因为会话读多写多、要求低延迟且天然带过期时间，重启不丢、多实例共享；不可用时自动降级为内存存储，
 * 保证没有 Redis 的本地环境仍能启动与调试。
 */
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisChatMemoryStore.class);

    private final RedisAvailability redisAvailability;

    private final ChatMemoryProperties properties;

    private final ObjectMapper objectMapper;

    /** Redis 不可用时的降级存储 */
    private final ChatMemoryStore fallbackStore;

    public RedisChatMemoryStore(RedisAvailability redisAvailability,
                                ChatMemoryProperties properties,
                                ObjectMapper objectMapper,
                                ChatMemoryStore fallbackStore) {
        this.redisAvailability = redisAvailability;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.fallbackStore = fallbackStore;
    }

    @Override
    public Optional<ChatSessionState> load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }

        if (!redisAvailability.isAvailable()) {
            return fallbackStore.load(sessionId);
        }

        try {
            String json = redisAvailability.getRedisTemplate().opsForValue().get(buildKey(sessionId));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, ChatSessionState.class));
        } catch (JsonProcessingException e) {
            // 数据格式问题（例如历史遗留的旧 JSON 结构）：只影响这一条记录。
            // 绝不能在数据错误时调用 markUnavailable——那会因为一条脏数据把整个 Redis 存储永久禁用。
            logger.error("会话数据反序列化失败，已忽略该记录（Redis 保持可用）: sessionId={}, 原因={}",
                    sessionId, e.getOriginalMessage());
            return Optional.empty();
        } catch (Exception e) {
            logger.warn("从 Redis 读取会话失败，本次降级为内存存储: {}", e.getMessage());
            redisAvailability.markUnavailable(e.getMessage());
            return fallbackStore.load(sessionId);
        }
    }

    @Override
    public void save(ChatSessionState state) {
        if (state == null || state.getSessionId() == null || state.getSessionId().isBlank()) {
            logger.warn("会话状态缺少 sessionId，已忽略保存请求");
            return;
        }

        if (!redisAvailability.isAvailable()) {
            fallbackStore.save(state);
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(state);
            // 每次写入续期，活跃会话不会因为过期时间到点而被清掉
            redisAvailability.getRedisTemplate().opsForValue()
                    .set(buildKey(state.getSessionId()), json, resolveTtl());
        } catch (JsonProcessingException e) {
            // 序列化失败同样只影响本次写入，不牵连 Redis 的可用性判断
            logger.error("会话序列化失败，本次仅写入内存存储（Redis 保持可用）: sessionId={}, 原因={}",
                    state.getSessionId(), e.getOriginalMessage());
            fallbackStore.save(state);
        } catch (Exception e) {
            logger.warn("写入 Redis 会话失败，本次降级为内存存储: {}", e.getMessage());
            redisAvailability.markUnavailable(e.getMessage());
            fallbackStore.save(state);
        }
    }

    @Override
    public void delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        if (redisAvailability.isAvailable()) {
            try {
                redisAvailability.getRedisTemplate().delete(buildKey(sessionId));
            } catch (Exception e) {
                logger.warn("删除 Redis 会话失败: {}", e.getMessage());
            }
        }
        fallbackStore.delete(sessionId);
    }

    @Override
    public String describe() {
        return redisAvailability.isAvailable() ? "Redis" : "内存（Redis 当前不可用，已降级）";
    }

    @Override
    public List<ChatSessionState> listSessions(int limit) {
        if (!redisAvailability.isAvailable()) {
            return fallbackStore.listSessions(limit);
        }

        try {
            List<String> keys = scanSessionKeys(limit);
            if (keys.isEmpty()) {
                return new ArrayList<>();
            }

            List<String> jsonValues = redisAvailability.getRedisTemplate().opsForValue().multiGet(keys);
            if (jsonValues == null || jsonValues.isEmpty()) {
                return new ArrayList<>();
            }

            List<ChatSessionState> sessions = new ArrayList<>(jsonValues.size());
            for (String json : jsonValues) {
                if (json == null || json.isBlank()) {
                    continue;
                }
                try {
                    sessions.add(objectMapper.readValue(json, ChatSessionState.class));
                } catch (JsonProcessingException e) {
                    // 单条脏数据只跳过这一条，不影响整个列表
                    logger.warn("列会话时跳过一条无法解析的记录: {}", e.getOriginalMessage());
                }
            }

            sessions.sort((left, right) ->
                    InMemoryChatMemoryStore.compareUpdatedAtDesc(left.getUpdatedAt(), right.getUpdatedAt()));

            int safeLimit = Math.max(limit, 1);
            return sessions.size() > safeLimit
                    ? new ArrayList<>(sessions.subList(0, safeLimit))
                    : sessions;

        } catch (Exception e) {
            logger.warn("从 Redis 列出会话失败，本次降级为内存存储: {}", e.getMessage());
            return fallbackStore.listSessions(limit);
        }
    }

    /**
     * 按前缀扫描会话键（用 SCAN 而不是 KEYS：KEYS 会阻塞 Redis 主线程，会话一多就影响线上读写）
     *
     * @param limit 期望返回的会话数
     * @return 键名列表
     */
    private List<String> scanSessionKeys(int limit) {
        List<String> keys = new ArrayList<>();
        String pattern = properties.getRedisKeyPrefix() + "*";
        int maxKeys = Math.max(limit, 1) * 3;

        RedisCallback<Void> scanCallback = connection -> {
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(200).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext() && keys.size() < maxKeys) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                logger.warn("扫描会话键失败: {}", e.getMessage());
            }
            return null;
        };

        redisAvailability.getRedisTemplate().execute(scanCallback);
        return keys;
    }

    /**
     * 拼装 Redis 键名
     *
     * @param sessionId 会话 ID
     * @return 完整键名
     */
    private String buildKey(String sessionId) {
        return properties.getRedisKeyPrefix() + sessionId;
    }

    /**
     * 计算会话过期时间
     *
     * @return TTL
     */
    private Duration resolveTtl() {
        return Duration.ofDays(Math.max(properties.getRedisTtlDays(), 1));
    }

    /**
     * 暴露 Redis 模板（供测试与扩展使用）
     *
     * @return Redis 字符串模板
     */
    StringRedisTemplate redisTemplate() {
        return redisAvailability.getRedisTemplate();
    }
}
