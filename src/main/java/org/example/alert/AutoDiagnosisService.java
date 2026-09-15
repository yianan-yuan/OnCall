package org.example.alert;

import org.springframework.ai.chat.model.ChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 告警自动诊断服务
 * <p>
 * Alertmanager 通过 Webhook 推来告警后，在后台自动跑一轮 AIOps 多 Agent 排查，并把报告存进运行记录供查询。
 * 用指纹去重保证同一告警只诊断一次，用信号量限制并发，避免告警风暴把大模型配额与线程打满。
 */
@Service
public class AutoDiagnosisService {

    private static final Logger logger = LoggerFactory.getLogger(AutoDiagnosisService.class);

    private static final long SSE_TIMEOUT_MILLIS = 30 * 60 * 1000L;  // SSE 连接超时（30 分钟，够看完一轮排查）

    private final ChatService chatService;

    private final AiOpsService aiOpsService;

    private final AlertDeduplicationService deduplicationService;

    private final AlertWebhookProperties properties;

    private final ExecutorService diagnosisExecutor;  // 诊断执行线程池

    private final Semaphore concurrencyLimiter;  // 并发限流器

    private final Map<String, AutoDiagnosisRun> runs = new ConcurrentHashMap<>();  // 运行记录：运行 ID → 记录

    private final Deque<String> runOrder = new ArrayDeque<>();  // 运行记录顺序，用于倒序返回并淘汰最旧记录

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();  // 订阅自动诊断事件的 SSE 连接

    public AutoDiagnosisService(ChatService chatService,
                                AiOpsService aiOpsService,
                                AlertDeduplicationService deduplicationService,
                                AlertWebhookProperties properties) {
        this.chatService = chatService;
        this.aiOpsService = aiOpsService;
        this.deduplicationService = deduplicationService;
        this.properties = properties;

        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "auto-diagnosis-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
        this.diagnosisExecutor = Executors.newCachedThreadPool(threadFactory);
        this.concurrencyLimiter = new Semaphore(Math.max(properties.getMaxConcurrentDiagnoses(), 1));

        logger.info("✅ 告警自动诊断服务初始化完成, 最大并发: {}, 去重窗口: {} 分钟",
                properties.getMaxConcurrentDiagnoses(), properties.getDedupTtlMinutes());
    }

    /** 关闭诊断线程池并断开所有 SSE 连接 */
    @PreDestroy
    public void destroy() {
        diagnosisExecutor.shutdownNow();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 关闭阶段的异常无需处理
            }
        }
        emitters.clear();
    }

    /**
     * 提交一批告警：去重后异步触发诊断
     *
     * @param alerts Alertmanager 推送的告警列表
     * @return 本次处理的运行记录（包含 SKIPPED 状态），按提交顺序
     */
    public List<AutoDiagnosisRun> submitBatch(List<AlertmanagerAlert> alerts) {
        List<AutoDiagnosisRun> submitted = new ArrayList<>();

        if (alerts == null || alerts.isEmpty()) {
            return submitted;
        }

        for (AlertmanagerAlert alert : alerts) {
            if (alert == null) {
                continue;
            }

            // 已恢复的告警不需要排查（Alertmanager 配置了 send_resolved=true 时会推过来）
            if (!"firing".equalsIgnoreCase(alert.getStatus())) {
                logger.debug("跳过非 firing 告警: status={}, alert={}",
                        alert.getStatus(), alert.resolveAlertName());
                continue;
            }

            String deduplicationKey = alert.resolveDeduplicationKey("alertmanager");
            AutoDiagnosisRun run = createRunRecord(alert, deduplicationKey);

            if (!deduplicationService.markIfNew(deduplicationKey)) {
                run.setStatus(AutoDiagnosisRun.Status.SKIPPED);
                run.setFinishedAt(Instant.now().toString());
                logger.info("告警重复，跳过诊断: alert={}, service={}, fingerprint={}",
                        run.getAlertName(), run.getService(), deduplicationKey);
                registerRun(run);
                submitted.add(run);
                continue;
            }

            run.setStatus(AutoDiagnosisRun.Status.QUEUED);
            registerRun(run);
            submitted.add(run);

            diagnosisExecutor.submit(() -> executeDiagnosis(run, alert));
        }

        return submitted;
    }

    /**
     * 执行一次诊断（在后台线程中运行）
     *
     * @param run   运行记录
     * @param alert 触发的告警
     */
    private void executeDiagnosis(AutoDiagnosisRun run, AlertmanagerAlert alert) {
        boolean acquired = false;
        Instant start = Instant.now();

        try {
            // 并发限流：拿不到许可就在这里排队等待
            concurrencyLimiter.acquire();
            acquired = true;

            run.setStatus(AutoDiagnosisRun.Status.RUNNING);
            run.setStartedAt(start.toString());
            broadcast(run);

            logger.info("开始自动诊断: alert={}, service={}, runId={}",
                    run.getAlertName(), run.getService(), run.getId());

            ChatModel chatModel = chatService.createAiOpsChatModel();
            // 走 ChatService 取工具：这样 chat.use-mcp-tools=false 时能统一屏蔽 MCP 工具
            ToolCallback[] toolCallbacks = chatService.getToolCallbacks();

            Optional<OverAllState> state = aiOpsService.executeAiOpsAnalysis(
                    chatModel, toolCallbacks, buildTaskPrompt(alert));

            Optional<String> report = state.flatMap(aiOpsService::extractFinalReport);

            if (report.isPresent() && !report.get().isBlank()) {
                run.setReport(report.get());
                run.setStatus(AutoDiagnosisRun.Status.SUCCEEDED);
                logger.info("自动诊断完成: runId={}, 报告长度={}", run.getId(), report.get().length());
            } else {
                run.setStatus(AutoDiagnosisRun.Status.FAILED);
                run.setErrorMessage("多 Agent 编排未产出最终报告");
                logger.warn("自动诊断未产出报告: runId={}", run.getId());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.setStatus(AutoDiagnosisRun.Status.FAILED);
            run.setErrorMessage("诊断任务被中断");
        } catch (Exception e) {
            // 失败要如实记录，不能伪造报告
            run.setStatus(AutoDiagnosisRun.Status.FAILED);
            run.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            logger.error("自动诊断失败: runId={}", run.getId(), e);
        } finally {
            if (acquired) {
                concurrencyLimiter.release();
            }
            Instant end = Instant.now();
            run.setFinishedAt(end.toString());
            run.setDurationMillis(Duration.between(start, end).toMillis());
            broadcast(run);
        }
    }

    /**
     * 构建本次排查的任务提示词，把具体告警信息带进多 Agent 编排
     *
     * @param alert 告警
     * @return 任务提示词
     */
    private String buildTaskPrompt(AlertmanagerAlert alert) {
        return String.format("""
                你是企业级 SRE，刚收到一条由监控系统自动上报的告警，请立即排查并输出《告警分析报告》。

                ## 本次告警
                - 告警名称：%s
                - 告警级别：%s
                - 受影响服务：%s
                - 开始时间：%s
                - 告警描述：%s

                ## 要求
                请结合工具调用，执行**规划→执行→再规划**的闭环：
                先检索知识库中与该告警对应的处理手册，再按步骤查询日志与指标验证假设，
                最后按固定模板输出《告警分析报告》。
                禁止编造虚假数据，只能引用工具真实返回的内容；
                如果连续多次查询失败，请如实说明无法完成的原因，不要跳过。
                """,
                alert.resolveAlertName(),
                alert.resolveSeverity(),
                alert.resolveService(),
                alert.getStartsAt() == null ? "未知" : alert.getStartsAt(),
                alert.resolveDescription());
    }

    /**
     * 创建运行记录
     *
     * @param alert            告警
     * @param deduplicationKey 去重键
     * @return 运行记录
     */
    private AutoDiagnosisRun createRunRecord(AlertmanagerAlert alert, String deduplicationKey) {
        AutoDiagnosisRun run = new AutoDiagnosisRun();
        run.setId(UUID.randomUUID().toString());
        run.setFingerprint(deduplicationKey);
        run.setAlertName(alert.resolveAlertName());
        run.setService(alert.resolveService());
        run.setSeverity(alert.resolveSeverity());
        run.setDescription(alert.resolveDescription());
        return run;
    }

    /**
     * 登记运行记录，并淘汰最旧的记录
     *
     * @param run 运行记录
     */
    private synchronized void registerRun(AutoDiagnosisRun run) {
        runs.put(run.getId(), run);
        runOrder.addLast(run.getId());

        int maxRecentRuns = Math.max(properties.getMaxRecentRuns(), 1);
        while (runOrder.size() > maxRecentRuns) {
            String oldestId = runOrder.pollFirst();
            if (oldestId != null) {
                runs.remove(oldestId);
            }
        }
    }

    /**
     * 获取最近的运行记录（新的在前）
     *
     * @return 运行记录列表
     */
    public synchronized List<AutoDiagnosisRun> listRecentRuns() {
        List<AutoDiagnosisRun> ordered = new ArrayList<>(runOrder.size());
        // 从队尾往前取，得到"新的在前"的顺序
        java.util.Iterator<String> iterator = runOrder.descendingIterator();
        while (iterator.hasNext()) {
            AutoDiagnosisRun run = runs.get(iterator.next());
            if (run != null) {
                ordered.add(run);
            }
        }
        return ordered;
    }

    /**
     * 按 ID 查询运行记录
     *
     * @param runId 运行 ID
     * @return 运行记录
     */
    public Optional<AutoDiagnosisRun> findRun(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    /**
     * 订阅自动诊断事件流：新提交、开始执行、执行结束都会推送
     *
     * @return SSE 连接
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(throwable -> emitters.remove(emitter));

        emitters.add(emitter);

        // 订阅时先把最近记录推一遍，方便页面刷新后立刻看到现状
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(listRecentRuns()));
        } catch (IOException e) {
            emitters.remove(emitter);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * 向所有订阅者广播运行状态
     *
     * @param run 运行记录
     */
    private void broadcast(AutoDiagnosisRun run) {
        if (emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("run").data(run));
            } catch (Exception e) {
                // 连接已断开：移除即可，不影响诊断本身
                emitters.remove(emitter);
            }
        }
    }
}
