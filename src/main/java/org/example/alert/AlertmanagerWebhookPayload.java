package org.example.alert;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Alertmanager Webhook 报文体
 * <p>
 * Alertmanager 配置 webhook 接收器后，会把同一分组的告警打包成这个结构推送过来。
 */
@Setter
@Getter
public class AlertmanagerWebhookPayload {

    private String version;  // 报文版本（Alertmanager 固定为 "4"）

    private String groupKey;  // 分组标识：同一分组的告警一起推送

    private String status;  // 状态：firing / resolved

    private String receiver;  // 接收器名称

    private List<AlertmanagerAlert> alerts = new ArrayList<>();  // 告警列表（同一分组可能有多条）

    private Map<String, String> groupLabels = new HashMap<>();  // 分组标签

    private Map<String, String> commonLabels = new HashMap<>();  // 公共标签

    private Map<String, String> commonAnnotations = new HashMap<>();  // 公共注释

    /**
     * 获取有效的告警列表
     *
     * @return 非空列表；字段缺失时返回空列表而不是 null
     */
    public List<AlertmanagerAlert> safeAlerts() {
        return alerts == null ? new ArrayList<>() : alerts;
    }
}
