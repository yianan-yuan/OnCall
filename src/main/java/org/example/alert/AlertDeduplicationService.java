package org.example.alert;

import org.example.memory.RedisAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警去重服务（幂等保证）
 * <p>
 * Alertmanager 会反复推送未恢复的告警，这里用指纹作键，在去重窗口内只放行第一次。
 * 优先用 Redis 的 {@code SET NX + EXPIRE}（原生支持多实例），不可用时降级为进程内 Map，
 * 此时去重只在单个进程内有效，多实例部署需要 Redis。
 */
@Service
public class AlertDeduplicationService {

    private static final Logger logger = LoggerFactory.getLogger(AlertDeduplicationService.class);

    /** Redis 键前缀 */
    private static final String REDIS_KEY_PREFIX = "alert:webhook:dedup:";

    private final RedisAvailability redisAvailability;

    private final AlertWebhookProperties properties;

    private final Map<String, Long> memoryDedupTable = new ConcurrentHashMap<>();  // 内存降级用的去重表：键 → 过期时间戳（毫秒）

    public AlertDeduplicationService(RedisAvailability redisAvailability,
                                     AlertWebhookProperties properties) {
        this.redisAvailability = redisAvailability;
        this.properties = properties;
    }

    /**
     * 尝试占位：只有第一次调用会返回 true
     *
     * @param deduplicationKey 去重键（告警指纹）
     * @return true 表示这是首次出现，可以继续触发诊断；false 表示重复告警，应跳过
     */
    public boolean markIfNew(String deduplicationKey) {
        if (deduplicationKey == null || deduplicationKey.isBlank()) {
            // 没有可用键时不做去重，宁可多跑一次也不要漏诊
            return true;
        }

        Duration ttl = Duration.ofMinutes(Math.max(properties.getDedupTtlMinutes(), 1));

        if (redisAvailability.isAvailable()) {
            try {
                Boolean created = redisAvailability.getRedisTemplate()
                        .opsForValue()
                        .setIfAbsent(REDIS_KEY_PREFIX + deduplicationKey, "1", ttl);
                return Boolean.TRUE.equals(created);
            } catch (Exception e) {
                logger.warn("告警去重写入 Redis 失败，本次降级为内存去重: {}", e.getMessage());
                redisAvailability.markUnavailable(e.getMessage());
            }
        }

        return markInMemory(deduplicationKey, ttl.toMillis());
    }

    /**
     * 内存去重，顺带清理过期项避免 Map 无限增长
     *
     * @param key       去重键
     * @param ttlMillis 过期时间（毫秒）
     * @return true 表示首次出现
     */
    private boolean markInMemory(String key, long ttlMillis) {
        long now = System.currentTimeMillis();
        purgeExpired(now);

        Long expiresAt = memoryDedupTable.get(key);
        if (expiresAt != null && expiresAt > now) {
            return false;
        }

        memoryDedupTable.put(key, now + ttlMillis);
        return true;
    }

    /**
     * 清理已过期的去重项
     *
     * @param now 当前时间戳（毫秒）
     */
    private void purgeExpired(long now) {
        Iterator<Map.Entry<String, Long>> iterator = memoryDedupTable.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= now) {
                iterator.remove();
            }
        }
    }

    /** 清空内存去重记录（仅用于测试与本地调试） */
    public void clear() {
        memoryDedupTable.clear();
    }
}
