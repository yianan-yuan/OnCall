package org.example.alert;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * Alertmanager 推送过来的一条告警
 * <p>
 * 对应 Webhook（v4 报文）alerts 数组的每一项，只保留排查真正需要的字段。
 */
@Setter
@Getter
public class AlertmanagerAlert {

    private String status;  // firing（触发中）/ resolved（已恢复）

    private Map<String, String> labels = new HashMap<>();  // 标签集合，alertname 为告警名，service 为受影响服务

    private Map<String, String> annotations = new HashMap<>();  // 注释集合，通常含 summary 与 description

    private String startsAt;  // 告警开始时间（ISO8601）

    private String endsAt;  // 告警恢复时间（ISO8601），未恢复时为空

    private String fingerprint;  // Alertmanager 计算的指纹，同一告警重复推送时不变，用于幂等去重

    /** 获取告警名：labels.alertname，缺失时返回 "UnknownAlert" */
    public String resolveAlertName() {
        String value = labels == null ? null : labels.get("alertname");
        return value == null || value.isBlank() ? "UnknownAlert" : value;
    }

    /** 获取受影响服务：依次取 labels.service、labels.job，都没有时返回 "unspecified-service" */
    public String resolveService() {
        if (labels != null) {
            String service = labels.get("service");
            if (service != null && !service.isBlank()) {
                return service;
            }
            String job = labels.get("job");
            if (job != null && !job.isBlank()) {
                return job;
            }
        }
        return "unspecified-service";
    }

    /** 获取告警级别：labels.severity，缺失时返回 "unknown" */
    public String resolveSeverity() {
        String value = labels == null ? null : labels.get("severity");
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /** 获取告警描述：annotations.description，缺失时退回 annotations.summary */
    public String resolveDescription() {
        if (annotations == null) {
            return "";
        }
        String description = annotations.get("description");
        if (description != null && !description.isBlank()) {
            return description;
        }
        String summary = annotations.get("summary");
        return summary == null ? "" : summary;
    }

    /**
     * 生成去重用的标识：优先使用 Alertmanager 指纹
     *
     * @param fallbackPrefix 指纹缺失时的兜底前缀
     * @return 去重标识
     */
    public String resolveDeduplicationKey(String fallbackPrefix) {
        if (fingerprint != null && !fingerprint.isBlank()) {
            return fingerprint;
        }
        // 指纹缺失时用"告警名 + 服务 + 开始时间"构造，保证同一告警仍能去重
        return fallbackPrefix + ":" + resolveAlertName() + ":" + resolveService() + ":" + startsAt;
    }
}
