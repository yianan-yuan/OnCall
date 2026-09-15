package org.example.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 告警报文解析单元测试
 * <p>
 * Alertmanager 的报文里，告警名与服务名藏在 labels 中，描述藏在 annotations 中，
 * 而且不同来源的字段完整度不一样。这些取值逻辑直接决定排查任务提示词的内容，
 * 因此用测试固定住兜底行为。
 */
class AlertmanagerAlertTest {

    /**
     * 构造一条告警
     *
     * @param labels      标签
     * @param annotations 注释
     * @return 告警对象
     */
    private AlertmanagerAlert buildAlert(Map<String, String> labels, Map<String, String> annotations) {
        AlertmanagerAlert alert = new AlertmanagerAlert();
        alert.setLabels(labels);
        alert.setAnnotations(annotations);
        alert.setStatus("firing");
        return alert;
    }

    @Test
    @DisplayName("正常解析告警名、服务与级别")
    void shouldResolveBasicFields() {
        Map<String, String> labels = new HashMap<>();
        labels.put("alertname", "HighCPUUsage");
        labels.put("service", "payment-service");
        labels.put("severity", "critical");

        AlertmanagerAlert alert = buildAlert(labels, new HashMap<>());

        assertThat(alert.resolveAlertName()).isEqualTo("HighCPUUsage");
        assertThat(alert.resolveService()).isEqualTo("payment-service");
        assertThat(alert.resolveSeverity()).isEqualTo("critical");
    }

    @Test
    @DisplayName("服务缺失时退回 job 标签，再缺失时给出占位值")
    void shouldFallbackForService() {
        Map<String, String> withJob = new HashMap<>();
        withJob.put("alertname", "HighMemoryUsage");
        withJob.put("job", "node");
        assertThat(buildAlert(withJob, new HashMap<>()).resolveService()).isEqualTo("node");

        assertThat(buildAlert(new HashMap<>(), new HashMap<>()).resolveService())
                .isEqualTo("unspecified-service");
        assertThat(buildAlert(new HashMap<>(), new HashMap<>()).resolveAlertName())
                .isEqualTo("UnknownAlert");
        assertThat(buildAlert(new HashMap<>(), new HashMap<>()).resolveSeverity())
                .isEqualTo("unknown");
    }

    @Test
    @DisplayName("描述优先取 description，其次 summary")
    void shouldPreferDescriptionOverSummary() {
        Map<String, String> annotations = new HashMap<>();
        annotations.put("summary", "CPU 使用率过高");
        annotations.put("description", "服务 payment-service 的 CPU 使用率持续超过 80%，当前 92%。");

        assertThat(buildAlert(new HashMap<>(), annotations).resolveDescription())
                .contains("92%");

        Map<String, String> onlySummary = new HashMap<>();
        onlySummary.put("summary", "CPU 使用率过高");
        assertThat(buildAlert(new HashMap<>(), onlySummary).resolveDescription())
                .isEqualTo("CPU 使用率过高");
    }

    @Test
    @DisplayName("去重键优先使用 Alertmanager 指纹")
    void shouldPreferFingerprintForDeduplication() {
        AlertmanagerAlert alert = buildAlert(new HashMap<>(), new HashMap<>());
        alert.setFingerprint("abc123");

        assertThat(alert.resolveDeduplicationKey("alertmanager")).isEqualTo("abc123");
    }

    @Test
    @DisplayName("指纹缺失时用告警名 + 服务 + 开始时间构造去重键")
    void shouldBuildFallbackDeduplicationKey() {
        Map<String, String> labels = new HashMap<>();
        labels.put("alertname", "SlowResponse");
        labels.put("service", "user-service");

        AlertmanagerAlert alert = buildAlert(labels, new HashMap<>());
        alert.setStartsAt("2026-09-14T10:00:00Z");

        assertThat(alert.resolveDeduplicationKey("alertmanager"))
                .isEqualTo("alertmanager:SlowResponse:user-service:2026-09-14T10:00:00Z");
    }

    @Test
    @DisplayName("告警列表字段缺失时返回空列表而不是 null")
    void shouldReturnEmptyListWhenAlertsMissing() {
        AlertmanagerWebhookPayload payload = new AlertmanagerWebhookPayload();
        payload.setAlerts(null);

        assertThat(payload.safeAlerts()).isEmpty();
    }
}
