package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.RetrievalHit;
import org.example.retrieval.RetrievalException;
import org.example.service.KnowledgeRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部文档查询工具
 * <p>
 * 背后走 {@link KnowledgeRetrievalService} 的混合检索流水线：向量召回 + BM25 关键词召回
 * → RRF 融合 → 精排；返回每条命中的正文、来源与三阶段名次分数，便于模型判断命中类型。
 */
@Component
public class InternalDocsTools {

    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);

    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";

    /** 引用摘要的最大长度（与 Python 参考实现一致） */
    private static final int MAX_CITATION_EXCERPT_LENGTH = 480;

    private final KnowledgeRetrievalService knowledgeRetrievalService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造函数注入依赖
     *
     * @param knowledgeRetrievalService 混合检索服务
     */
    @Autowired
    public InternalDocsTools(KnowledgeRetrievalService knowledgeRetrievalService) {
        this.knowledgeRetrievalService = knowledgeRetrievalService;
    }

    /**
     * 查询内部文档工具：向量召回语义相近的问法，BM25 召回错误码、API 路径等精确标识符
     *
     * @param query 搜索查询，描述您要查找的信息
     * @return JSON 格式的搜索结果，包含分片内容、来源、三阶段名次与分数
     */
    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
            "It performs hybrid retrieval (semantic vector search plus BM25 keyword search, fused with reciprocal rank " +
            "fusion and reranked by a rerank model) to find the most relevant document chunks. " +
            "It handles both natural-language questions and exact identifiers such as error codes, API paths and " +
            "service names. Use it when you need internal procedures, best practices, or step-by-step guides. " +
            "Each returned item includes its source and the per-stage ranking so answers can cite evidence.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for")
            String query) {

        try {
            // 走混合检索流水线；topK 由 retrieval.top-k 配置控制，模型无法突破硬上限
            List<RetrievalHit> hits = knowledgeRetrievalService.retrieve(query);

            if (hits.isEmpty()) {
                // 两路都没有候选：这是正常的"知识库无相关内容"，不是系统错误
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }

            Map<String, Object> payload = buildResultPayload(query, hits);
            return objectMapper.writeValueAsString(payload);

        } catch (RetrievalException e) {
            // 混合检索任一环节失败：如实返回系统错误，绝不降级成单路检索后假装成功
            logger.error("[工具错误] queryInternalDocs 执行失败: {}", e.getMessage());
            return String.format(
                    "{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}",
                    e.getMessage());
        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format(
                    "{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}",
                    e.getMessage());
        }
    }

    /**
     * 构建工具返回的 JSON 结构
     * <p>
     * 与 Python 参考实现保持一致：{@code results} 是检索命中，{@code citations} 是对应的引用来源。
     *
     * @param query 原始查询
     * @param hits  检索结果
     * @return 可直接序列化的 Map
     */
    private Map<String, Object> buildResultPayload(String query, List<RetrievalHit> hits) {
        List<Map<String, Object>> results = new ArrayList<>(hits.size());
        List<Map<String, Object>> citations = new ArrayList<>(hits.size());

        int rank = 1;
        for (RetrievalHit hit : hits) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rank", rank);
            result.put("chunkId", hit.getChunkId());
            result.put("content", hit.getContent());
            result.put("source", hit.getSource());
            result.put("metadata", hit.getMetadata());
            result.put("score", hit.getScore());
            result.put("vectorRank", hit.getVectorRank());
            result.put("bm25Rank", hit.getBm25Rank());
            result.put("rerankRank", hit.getRerankRank());
            result.put("vectorScore", hit.getVectorScore());
            result.put("bm25Score", hit.getBm25Score());
            result.put("rrfScore", hit.getRrfScore());
            result.put("rerankScore", hit.getRerankScore());
            results.add(result);

            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("id", hit.getChunkId());
            citation.put("title", resolveCitationTitle(hit));
            citation.put("sourceType", "knowledge-base");
            citation.put("chunkId", hit.getChunkId());
            citation.put("source", hit.getSource());
            citation.put("score", hit.getScore());
            citation.put("excerpt", buildExcerpt(hit.getContent()));
            citations.add(citation);

            rank++;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("topK", hits.size());
        payload.put("results", results);
        payload.put("citations", citations);
        return payload;
    }

    /**
     * 解析引用的展示标题：优先用来源文件名，其次退回分片 ID
     *
     * @param hit 检索结果
     * @return 标题文本
     */
    private String resolveCitationTitle(RetrievalHit hit) {
        if (hit.getSource() != null && !hit.getSource().isBlank()) {
            return hit.getSource();
        }
        return hit.getChunkId();
    }

    /**
     * 生成引用摘要：压缩空白并截断到最大长度
     *
     * @param content 分片正文
     * @return 摘要文本
     */
    private String buildExcerpt(String content) {
        if (content == null) {
            return "";
        }

        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_CITATION_EXCERPT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_CITATION_EXCERPT_LENGTH - 3) + "...";
    }
}
