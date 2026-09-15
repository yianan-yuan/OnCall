package org.example.alert;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 告警自动诊断配置
 * <p>
 * 对应 application.yml 中的 {@code alert.webhook.*} 配置段。
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "alert.webhook")
public class AlertWebhookProperties {

    private boolean enabled = true;  // 是否启用告警 Webhook 接收

    /**
     * 同时执行的最大诊断数量
     * <p>
     * 告警风暴时可能一次推来几十条告警，不限流会把大模型配额与线程一次性打满。
     */
    private int maxConcurrentDiagnoses = 2;

    private int dedupTtlMinutes = 30;  // 去重窗口（分钟）：Alertmanager 会重发未恢复的告警，同一指纹在窗口内只诊断一次

    private int maxRecentRuns = 50;  // 内存中保留的最近诊断记录数量

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setMaxConcurrentDiagnoses(int maxConcurrentDiagnoses) {
        this.maxConcurrentDiagnoses = maxConcurrentDiagnoses;
    }

    public void setDedupTtlMinutes(int dedupTtlMinutes) {
        this.dedupTtlMinutes = dedupTtlMinutes;
    }

    public void setMaxRecentRuns(int maxRecentRuns) {
        this.maxRecentRuns = maxRecentRuns;
    }
}
