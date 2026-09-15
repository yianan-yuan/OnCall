package org.example.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Redis 可用性探测器
 * <p>
 * 启动时探测一次 Redis 是否可用，供上下文记忆存储与告警去重使用：可用则走 Redis（重启不丢、多实例共享），
 * 不可用则降级为内存实现并打印告警。
 * <p>
 * 这里降级的是存储介质，不是检索链路：混合检索任一环节失败必须整体报错，两者不要混淆。
 */
@Component
public class RedisAvailability {

    private static final Logger logger = LoggerFactory.getLogger(RedisAvailability.class);

    private final StringRedisTemplate redisTemplate;

    /** 探测结果：true 表示 Redis 可用 */
    private volatile boolean available;

    public RedisAvailability(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 启动时探测 Redis 连接
     */
    @PostConstruct
    public void probe() {
        try {
            // 显式声明回调类型，避免 execute(RedisCallback) 与 execute(SessionCallback) 的重载歧义
            RedisCallback<String> pingCallback = connection -> connection.ping();
            String pong = redisTemplate.execute(pingCallback);
            this.available = pong != null;
            if (available) {
                logger.info("✅ Redis 可用，上下文记忆与告警去重将使用 Redis 存储");
            } else {
                logger.warn("⚠️ Redis 探测未返回结果，将降级为内存存储");
            }
        } catch (Exception e) {
            this.available = false;
            logger.warn("⚠️ 无法连接 Redis（{}），上下文记忆与告警去重将降级为内存存储；"
                    + "内存存储重启后会丢失，仅适合本地调试。启动 Redis 后重启应用即可恢复。",
                    e.getMessage());
        }
    }

    /** Redis 运行期是否可用 */
    public boolean isAvailable() {
        return available;
    }

    /** 运行期把 Redis 标记为不可用（例如执行命令时连接失败），之后请求直接走内存实现，避免每次等待连接超时 */
    public void markUnavailable(String reason) {
        if (available) {
            logger.warn("⚠️ Redis 运行期不可用（{}），后续请求将使用内存存储", reason);
        }
        this.available = false;
    }

    public StringRedisTemplate getRedisTemplate() {
        return redisTemplate;
    }
}
