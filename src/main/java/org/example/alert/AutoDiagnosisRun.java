package org.example.alert;

import lombok.Getter;
import lombok.Setter;

/**
 * 一次"告警自动诊断"的执行记录
 * <p>
 * Webhook 触发的诊断没有调用方在等结果，所以用运行记录承载执行状态与最终报告，
 * 否则"自动排查"就是黑盒：用户既看不到进展，也拿不到报告。
 */
@Setter
@Getter
public class AutoDiagnosisRun {

    /**
     * 运行状态
     */
    public enum Status {

        /** 已入队，等待执行 */
        QUEUED("排队中"),

        /** 正在执行 */
        RUNNING("排查中"),

        /** 执行成功 */
        SUCCEEDED("已完成"),

        /** 执行失败 */
        FAILED("失败"),

        /** 被去重跳过（同一告警在去重窗口内已诊断过） */
        SKIPPED("已跳过（重复告警）");

        private final String description;

        Status(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    private String id;  // 运行 ID

    private String fingerprint;  // 告警指纹（Alertmanager 提供），用于幂等去重

    private String alertName;  // 告警名，如 HighCPUUsage

    private String service;  // 受影响服务

    private String severity;  // 告警级别

    private String description;  // 告警描述

    private Status status;  // 运行状态

    /** 最终诊断报告（Markdown 文本），执行成功时才有值 */
    private String report;

    /** 失败原因，执行失败时才有值 */
    private String errorMessage;

    private String startedAt;  // 开始时间（ISO8601）

    private String finishedAt;  // 结束时间（ISO8601）

    private long durationMillis;  // 耗时（毫秒）

    @Override
    public String toString() {
        return "AutoDiagnosisRun{" +
                "id='" + id + '\'' +
                ", alertName='" + alertName + '\'' +
                ", service='" + service + '\'' +
                ", status=" + status +
                ", durationMillis=" + durationMillis +
                '}';
    }
}
