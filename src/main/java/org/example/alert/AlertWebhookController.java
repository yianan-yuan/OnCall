package org.example.alert;

import org.example.controller.ChatController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 告警自动诊断接口
 * <p>
 * 接收 Alertmanager Webhook 触发后台 AIOps 排查，并提供运行记录列表、单条详情与 SSE 实时事件流。
 * Webhook 只做校验、去重与入队后立即返回：一轮诊断要几十秒，同步等待会被判定超时并重发。
 */
@RestController
@RequestMapping("/api")
public class AlertWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(AlertWebhookController.class);

    private final AutoDiagnosisService autoDiagnosisService;

    private final AlertWebhookProperties properties;

    public AlertWebhookController(AutoDiagnosisService autoDiagnosisService,
                                  AlertWebhookProperties properties) {
        this.autoDiagnosisService = autoDiagnosisService;
        this.properties = properties;
    }

    /**
     * 接收 Alertmanager 的告警推送，立即返回受理结果，实际排查在后台异步执行
     *
     * @param payload Alertmanager Webhook 报文
     * @return 受理摘要：收到、触发与因重复跳过的条数
     */
    @PostMapping("/alertmanager/webhook")
    public ResponseEntity<Map<String, Object>> receiveAlerts(@RequestBody AlertmanagerWebhookPayload payload) {
        if (!properties.isEnabled()) {
            logger.warn("收到告警推送，但 alert.webhook.enabled=false，已忽略");
            return ResponseEntity.ok(buildAck(0, 0, 0, "告警自动诊断已关闭"));
        }

        List<AlertmanagerAlert> alerts = payload == null ? List.of() : payload.safeAlerts();
        logger.info("收到 Alertmanager 告警推送: 条数={}, 分组={}, 状态={}",
                alerts.size(),
                payload == null ? null : payload.getGroupKey(),
                payload == null ? null : payload.getStatus());

        List<AutoDiagnosisRun> runs = autoDiagnosisService.submitBatch(alerts);

        int triggered = 0;
        int skipped = 0;
        for (AutoDiagnosisRun run : runs) {
            if (run.getStatus() == AutoDiagnosisRun.Status.SKIPPED) {
                skipped++;
            } else {
                triggered++;
            }
        }

        return ResponseEntity.ok(buildAck(alerts.size(), triggered, skipped, "已受理"));
    }

    /**
     * 订阅自动诊断事件流（SSE）
     *
     * @return SSE 连接；事件类型为 snapshot（订阅时的最近记录）与 run（运行状态变化）
     */
    @GetMapping(value = "/ai_ops/auto/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamAutoDiagnosis() {
        logger.info("有客户端订阅自动诊断事件流");
        return autoDiagnosisService.subscribe();
    }

    /**
     * 查询最近的自动诊断记录
     *
     * @return 运行记录列表（新的在前）
     */
    @GetMapping("/ai_ops/auto/runs")
    public ResponseEntity<ChatController.ApiResponse<List<AutoDiagnosisRun>>> listRuns() {
        return ResponseEntity.ok(ChatController.ApiResponse.success(autoDiagnosisService.listRecentRuns()));
    }

    /**
     * 查询单条自动诊断记录（含完整报告）
     *
     * @param runId 运行 ID
     * @return 运行记录
     */
    @GetMapping("/ai_ops/auto/runs/{runId}")
    public ResponseEntity<ChatController.ApiResponse<AutoDiagnosisRun>> getRun(@PathVariable String runId) {
        Optional<AutoDiagnosisRun> run = autoDiagnosisService.findRun(runId);
        return run.map(value -> ResponseEntity.ok(ChatController.ApiResponse.success(value)))
                .orElseGet(() -> ResponseEntity.ok(ChatController.ApiResponse.error("诊断记录不存在")));
    }

    /** 组装受理摘要：received / triggered / skipped 计数加提示信息 */
    private Map<String, Object> buildAck(int received, int triggered, int skipped, String message) {
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("status", "accepted");
        ack.put("message", message);
        ack.put("received", received);
        ack.put("triggered", triggered);
        ack.put("skipped", skipped);
        return ack;
    }
}
