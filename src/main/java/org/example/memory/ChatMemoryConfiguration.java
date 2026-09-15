package org.example.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 上下文记忆的存储装配
 * <p>
 * 按 {@code chat.memory.store} 选择：{@code redis}（默认，不可用时自动降级为内存）或 {@code memory}（本地调试，不想启动 Redis 时用）。
 */
@Configuration
public class ChatMemoryConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ChatMemoryConfiguration.class);

    /**
     * 创建会话存储
     *
     * @param properties         上下文记忆配置
     * @param redisAvailability  Redis 可用性探测器
     * @param objectMapper       JSON 序列化器
     * @return 会话存储实现
     */
    @Bean
    public ChatMemoryStore chatMemoryStore(ChatMemoryProperties properties,
                                           RedisAvailability redisAvailability,
                                           ObjectMapper objectMapper) {
        InMemoryChatMemoryStore inMemoryStore = new InMemoryChatMemoryStore();

        if (!"redis".equalsIgnoreCase(properties.getStore())) {
            logger.info("✅ 会话存储使用内存实现（chat.memory.store={}）", properties.getStore());
            return inMemoryStore;
        }

        RedisChatMemoryStore redisStore =
                new RedisChatMemoryStore(redisAvailability, properties, objectMapper, inMemoryStore);
        logger.info("✅ 会话存储使用 Redis 实现，键前缀: {}, TTL: {} 天",
                properties.getRedisKeyPrefix(), properties.getRedisTtlDays());
        return redisStore;
    }
}
