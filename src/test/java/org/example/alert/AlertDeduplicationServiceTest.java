package org.example.alert;

import org.example.memory.RedisAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 告警去重服务单元测试
 * <p>
 * 这里刻意不启动 Redis：{@link RedisAvailability} 未执行探测时视为不可用，
 * 服务会自动走内存去重分支，正好用来验证降级路径与幂等语义。
 */
class AlertDeduplicationServiceTest {

    /**
     * 构建一个走内存去重分支的服务实例
     *
     * @param ttlMinutes 去重窗口（分钟）
     * @return 服务实例
     */
    private AlertDeduplicationService buildService(int ttlMinutes) {
        AlertWebhookProperties properties = new AlertWebhookProperties();
        properties.setDedupTtlMinutes(ttlMinutes);

        // 未调用 probe()，因此 isAvailable() 为 false → 使用内存去重
        RedisAvailability availability = new RedisAvailability(mock(StringRedisTemplate.class));
        return new AlertDeduplicationService(availability, properties);
    }

    @Test
    @DisplayName("同一指纹第一次放行、第二次被判定为重复")
    void shouldAllowFirstOccurrenceOnly() {
        AlertDeduplicationService service = buildService(30);

        assertThat(service.markIfNew("fingerprint-a")).isTrue();
        assertThat(service.markIfNew("fingerprint-a")).isFalse();
        assertThat(service.markIfNew("fingerprint-a")).isFalse();
    }

    @Test
    @DisplayName("不同指纹互不影响")
    void shouldKeepDifferentFingerprintsIndependent() {
        AlertDeduplicationService service = buildService(30);

        assertThat(service.markIfNew("fingerprint-a")).isTrue();
        assertThat(service.markIfNew("fingerprint-b")).isTrue();
        assertThat(service.markIfNew("fingerprint-a")).isFalse();
        assertThat(service.markIfNew("fingerprint-b")).isFalse();
    }

    @Test
    @DisplayName("缺少指纹时不做去重，宁可多跑一次也不漏诊")
    void shouldAllowWhenKeyIsMissing() {
        AlertDeduplicationService service = buildService(30);

        assertThat(service.markIfNew(null)).isTrue();
        assertThat(service.markIfNew("  ")).isTrue();
    }

    @Test
    @DisplayName("清空去重记录后同一指纹可以再次通过")
    void shouldResetAfterClear() {
        AlertDeduplicationService service = buildService(30);

        assertThat(service.markIfNew("fingerprint-a")).isTrue();
        service.clear();
        assertThat(service.markIfNew("fingerprint-a")).isTrue();
    }
}
