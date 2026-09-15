package org.example.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.example.config.RetrievalProperties;
import org.example.retrieval.RerankException;
import org.example.retrieval.RerankModel;
import org.example.retrieval.RerankResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 阿里云百炼精排（rerank）客户端
 * <p>
 * 向量相似度与 BM25 只能算粗排，无法判断一段文字是否真的回答了问题，因此最终排序交给交叉编码器。
 * 失败一律抛 {@link RerankException}、不静默降级；响应下标与分数严格校验；只有限流、5xx 与
 * 网络超时才按指数退避重试。
 */
@Component
public class QwenRerankClient implements RerankModel {

    private static final Logger logger = LoggerFactory.getLogger(QwenRerankClient.class);

    /** 请求体类型 */
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    /** 重试基础退避时间（毫秒），与参考实现一致 */
    private static final long RETRY_BASE_DELAY_MILLIS = 250L;

    /** HTTP 限流状态码 */
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    /** HTTP 服务端错误下界 */
    private static final int HTTP_SERVER_ERROR = 500;

    /** 阿里云 API Key（与向量化、对话共用同一份配置） */
    @Value("${dashscope.api.key}")
    private String apiKey;

    private final RetrievalProperties retrievalProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OkHttpClient httpClient;

    public QwenRerankClient(RetrievalProperties retrievalProperties) {
        this.retrievalProperties = retrievalProperties;
    }

    /**
     * 初始化 HTTP 客户端
     */
    @PostConstruct
    public void init() {
        Duration timeout = Duration.ofSeconds(retrievalProperties.getRerankTimeoutSeconds());
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .build();

        logger.info("✅ 精排客户端初始化完成, model: {}, url: {}, 启用: {}",
                retrievalProperties.getRerankModel(),
                retrievalProperties.getRerankUrl(),
                retrievalProperties.isRerankEnabled());

        if (!retrievalProperties.isRerankEnabled()) {
            logger.warn("⚠️ 精排已通过配置关闭（retrieval.rerank-enabled=false），"
                    + "最终排序将直接使用 RRF 融合顺序。该开关只应用于本地调试，生产环境请保持开启。");
        }
    }

    /**
     * 对候选文档做精排
     *
     * @param query     用户查询
     * @param documents 候选文档内容列表
     * @param topN      需要返回的条数
     * @return 按相关度降序排列的结果
     */
    @Override
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        String normalizedQuery = query == null ? "" : query.strip();

        // 查询为空或没有候选时直接返回空列表，不发起请求（与参考实现一致）
        if (normalizedQuery.isEmpty() || documents == null || documents.isEmpty()) {
            return new ArrayList<>();
        }

        if (topN < 1 || topN > documents.size()) {
            throw new RerankException("精排请求参数非法：topN 必须在 1 到候选数量之间");
        }

        String payload = buildRequestPayload(normalizedQuery, documents, topN);
        JsonNode response = postWithRetry(payload);

        return parseRerankResults(response, documents.size(), topN);
    }

    /**
     * 构建精排请求体
     * <p>
     * 报文结构由阿里云百炼文本排序接口约定：{@code input.query.text} + {@code input.documents[].text}
     * + {@code parameters.top_n}；{@code return_documents} 固定为 false，只要下标和分数。
     *
     * @param query     查询文本
     * @param documents 候选文档
     * @param topN      返回条数
     * @return JSON 字符串
     */
    private String buildRequestPayload(String query, List<String> documents, int topN) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", retrievalProperties.getRerankModel());

        ObjectNode input = root.putObject("input");
        input.putObject("query").put("text", query);

        ArrayNode documentArray = input.putArray("documents");
        for (String document : documents) {
            documentArray.addObject().put("text", document == null ? "" : document);
        }

        ObjectNode parameters = root.putObject("parameters");
        parameters.put("return_documents", false);
        parameters.put("top_n", topN);

        return root.toString();
    }

    /**
     * 发送请求，遇到限流/服务端错误/网络超时按指数退避重试
     *
     * @param payload 请求体 JSON
     * @return 解析后的响应 JSON
     * @throws RerankException 重试耗尽或响应不可解析时抛出
     */
    private JsonNode postWithRetry(String payload) {
        int maxRetries = Math.max(retrievalProperties.getRerankMaxRetries(), 0);

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                Request request = new Request.Builder()
                        .url(retrievalProperties.getRerankUrl())
                        .header("Authorization", "Bearer " + apiKey)
                        .post(RequestBody.create(payload, JSON_MEDIA_TYPE))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    int statusCode = response.code();

                    // 限流与服务端错误属于可恢复错误：退避后重试
                    if ((statusCode == HTTP_TOO_MANY_REQUESTS || statusCode >= HTTP_SERVER_ERROR)
                            && attempt < maxRetries) {
                        logger.warn("精排请求返回 {}，第 {} 次重试", statusCode, attempt + 1);
                        sleepBeforeRetry(attempt);
                        continue;
                    }

                    if (!response.isSuccessful()) {
                        // 注意：不要把上游响应体写进异常消息，避免泄露细节
                        logger.error("精排请求失败, HTTP {}", statusCode);
                        throw new RerankException("精排服务暂时不可用");
                    }

                    ResponseBody body = response.body();
                    if (body == null) {
                        throw new RerankException("精排服务返回了空响应");
                    }

                    return objectMapper.readTree(body.string());
                }

            } catch (IOException e) {
                // 网络类异常：可重试
                if (attempt < maxRetries) {
                    logger.warn("精排请求网络异常（{}），第 {} 次重试", e.getClass().getSimpleName(), attempt + 1);
                    sleepBeforeRetry(attempt);
                    continue;
                }
                logger.error("精排请求网络异常且重试耗尽", e);
                throw new RerankException("精排服务暂时不可用", e);
            }
        }

        // 理论上不会走到这里（循环内一定会 return 或 throw）
        throw new RerankException("精排服务暂时不可用");
    }

    /**
     * 指数退避等待
     *
     * @param attempt 当前重试次数（从 0 开始）
     */
    private void sleepBeforeRetry(int attempt) {
        long delay = RETRY_BASE_DELAY_MILLIS * (1L << attempt);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            // 恢复中断标记，让上层能够感知线程被中断
            Thread.currentThread().interrupt();
            throw new RerankException("精排请求被中断", e);
        }
    }

    /**
     * 解析并校验精排响应
     *
     * @param response      响应 JSON
     * @param documentCount 候选文档数量，用于校验下标合法性
     * @param topN          期望返回条数
     * @return 按相关度降序排列的结果
     */
    private List<RerankResult> parseRerankResults(JsonNode response, int documentCount, int topN) {
        if (response == null || !response.isObject()) {
            throw new RerankException("精排服务返回了非法响应");
        }

        JsonNode output = response.get("output");
        if (output == null || !output.isObject()) {
            throw new RerankException("精排服务返回了非法响应");
        }

        JsonNode rawResults = output.get("results");
        if (rawResults == null || !rawResults.isArray()) {
            throw new RerankException("精排服务返回了非法响应");
        }

        List<RerankResult> results = new ArrayList<>();
        Set<Integer> seenIndexes = new HashSet<>();

        for (JsonNode rawResult : rawResults) {
            if (!rawResult.isObject()) {
                throw new RerankException("精排服务返回了非法响应");
            }

            JsonNode indexNode = rawResult.get("index");
            JsonNode scoreNode = rawResult.get("relevance_score");

            // 下标必须是整数、在候选范围内、且不能重复
            if (indexNode == null || !indexNode.isIntegralNumber()) {
                throw new RerankException("精排服务返回了非法响应");
            }
            int index = indexNode.asInt();
            if (index < 0 || index >= documentCount || seenIndexes.contains(index)) {
                throw new RerankException("精排服务返回了非法响应");
            }

            // 分数必须是数字、有限、且在 0~1 之间
            if (scoreNode == null || !scoreNode.isNumber()) {
                throw new RerankException("精排服务返回了非法响应");
            }
            double relevanceScore = scoreNode.asDouble();
            if (!Double.isFinite(relevanceScore) || relevanceScore < 0d || relevanceScore > 1d) {
                throw new RerankException("精排服务返回了非法响应");
            }

            seenIndexes.add(index);
            results.add(new RerankResult(index, relevanceScore));
        }

        // 按相关度降序，截取 topN
        results.sort((left, right) -> Double.compare(right.getRelevanceScore(), left.getRelevanceScore()));
        if (results.size() > topN) {
            return new ArrayList<>(results.subList(0, topN));
        }
        return results;
    }
}
