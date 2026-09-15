package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.RetrievalProperties;
import org.example.dto.RetrievalHit;
import org.example.dto.StoredVectorChunk;
import org.example.retrieval.Bm25DocumentRanker;
import org.example.retrieval.Bm25Rank;
import org.example.retrieval.ReciprocalRank;
import org.example.retrieval.ReciprocalRankFusion;
import org.example.retrieval.RerankModel;
import org.example.retrieval.RerankResult;
import org.example.retrieval.RetrievalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 混合检索服务（向量召回 + 关键词召回 + RRF 融合 + 精排）
 * <p>
 * 单一召回方式必然有盲区：纯向量召回会漏掉错误码、API 路径这类精确标识符，
 * 纯 BM25 又不理解同义表达，所以两路并行召回后按名次做 RRF 融合，再交精排模型排序。
 * 任一环节失败整体抛 {@link RetrievalException}，不降级为单路检索。
 */
@Service
public class KnowledgeRetrievalService {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeRetrievalService.class);

    /** 元数据中记录来源文件的字段名（与入库时的写法保持一致） */
    private static final String SOURCE_FIELD = "_source";

    private final VectorSearchService vectorSearchService;

    private final VectorChunkService vectorChunkService;

    private final RerankModel rerankModel;

    private final RetrievalProperties retrievalProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 两路召回使用的线程池（IO 密集，线程数不需要太多） */
    private ExecutorService recallExecutor;

    public KnowledgeRetrievalService(VectorSearchService vectorSearchService,
                                     VectorChunkService vectorChunkService,
                                     RerankModel rerankModel,
                                     RetrievalProperties retrievalProperties) {
        this.vectorSearchService = vectorSearchService;
        this.vectorChunkService = vectorChunkService;
        this.rerankModel = rerankModel;
        this.retrievalProperties = retrievalProperties;
    }

    /**
     * 初始化召回线程池
     */
    @PostConstruct
    public void init() {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "hybrid-recall-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
        this.recallExecutor = Executors.newFixedThreadPool(4, threadFactory);
        logger.info("✅ 混合检索服务初始化完成, topK: {}, RRF k: {}, 精排候选: {}",
                retrievalProperties.effectiveTopK(),
                retrievalProperties.getRrfK(),
                retrievalProperties.getRerankCandidateLimit());
    }

    /**
     * 关闭召回线程池
     */
    @PreDestroy
    public void destroy() {
        if (recallExecutor != null) {
            recallExecutor.shutdownNow();
        }
    }

    /**
     * 使用配置的 topK 执行混合检索
     *
     * @param query 查询文本
     * @return 检索结果，按精排分降序排列
     */
    public List<RetrievalHit> retrieve(String query) {
        return retrieve(query, null);
    }

    /**
     * 执行混合检索
     *
     * @param query          查询文本
     * @param requestedTopK  调用方期望的返回条数，可为 null；超过硬上限时会被截断
     * @return 检索结果，按精排分降序排列；两路都无候选时返回空列表
     * @throws RetrievalException 查询为空或任一环节失败时抛出
     */
    public List<RetrievalHit> retrieve(String query, Integer requestedTopK) {
        String normalizedQuery = query == null ? "" : query.strip();

        if (normalizedQuery.isEmpty()) {
            throw new RetrievalException("检索查询不能为空");
        }

        int topK = resolveTopK(requestedTopK);

        // ---------- 1. 两路并行召回 ----------
        long startTime = System.currentTimeMillis();
        CompletableFuture<List<VectorSearchService.SearchResult>> vectorFuture =
                CompletableFuture.supplyAsync(() -> recallByVector(normalizedQuery), recallExecutor);
        CompletableFuture<KeywordRecallResult> keywordFuture =
                CompletableFuture.supplyAsync(() -> recallByKeyword(normalizedQuery), recallExecutor);

        // 任一路失败都会让 join 抛异常；这里显式等待两路都结束，保证不出现"一路失败另一路继续跑"的悬空任务
        waitForBothRecalls(vectorFuture, keywordFuture);

        List<VectorSearchService.SearchResult> vectorHits = vectorFuture.join();
        KeywordRecallResult keywordRecall = keywordFuture.join();

        logger.info("两路召回完成：向量 {} 条，关键词 {} 条，耗时 {} ms",
                vectorHits.size(), keywordRecall.ranks.size(), System.currentTimeMillis() - startTime);

        // ---------- 2. RRF 融合 ----------
        List<RetrievalCandidate> candidates = fuseCandidates(vectorHits, keywordRecall);

        if (candidates.isEmpty()) {
            // 两路都没有候选，属于正常的"知识库无相关内容"，不是错误
            logger.info("混合检索未召回任何候选: {}", normalizedQuery);
            return Collections.emptyList();
        }

        // ---------- 3. 精排 ----------
        return rerankAndBuildHits(normalizedQuery, candidates, topK);
    }

    /**
     * 计算本次实际使用的返回条数（受硬上限约束）
     *
     * @param requestedTopK 调用方期望条数，可为 null
     * @return 生效的条数
     */
    private int resolveTopK(Integer requestedTopK) {
        int configured = retrievalProperties.effectiveTopK();
        if (requestedTopK == null) {
            return configured;
        }
        if (requestedTopK < 1) {
            throw new RetrievalException("topK 必须大于 0");
        }
        // 硬上限：模型要求更多也会被截断，防止上下文被塞爆
        return Math.min(requestedTopK, configured);
    }

    /**
     * 向量通道召回
     *
     * @param query 查询文本
     * @return 向量检索结果
     */
    private List<VectorSearchService.SearchResult> recallByVector(String query) {
        try {
            return vectorSearchService.searchSimilarDocuments(query, retrievalProperties.getVectorCandidateLimit());
        } catch (Exception e) {
            logger.error("向量召回失败", e);
            throw new CompletionException(new RetrievalException("知识检索暂时不可用", e));
        }
    }

    /**
     * 关键词通道召回：先枚举语料（只读标量字段），再用 BM25L 打分排序
     *
     * @param query 查询文本
     * @return 语料与 BM25 排名
     */
    private KeywordRecallResult recallByKeyword(String query) {
        try {
            List<StoredVectorChunk> chunks = vectorChunkService.listAllChunks();
            List<String> contents = chunks.stream()
                    .map(StoredVectorChunk::getContent)
                    .collect(Collectors.toList());

            List<Bm25Rank> ranks = Bm25DocumentRanker.rank(
                    query, contents, retrievalProperties.getBm25CandidateLimit());

            return new KeywordRecallResult(chunks, ranks);
        } catch (Exception e) {
            logger.error("关键词召回失败", e);
            throw new CompletionException(new RetrievalException("知识检索暂时不可用", e));
        }
    }

    /**
     * 等待两路召回结束；任一失败即整体失败
     *
     * @param vectorFuture  向量通道
     * @param keywordFuture 关键词通道
     */
    private void waitForBothRecalls(
            CompletableFuture<List<VectorSearchService.SearchResult>> vectorFuture,
            CompletableFuture<KeywordRecallResult> keywordFuture) {
        try {
            CompletableFuture.allOf(vectorFuture, keywordFuture).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RetrievalException) {
                throw (RetrievalException) cause;
            }
            throw new RetrievalException("知识检索暂时不可用", cause == null ? e : cause);
        }
    }

    /**
     * 把两路召回结果做 RRF 融合，并补全候选正文与原始分数
     *
     * @param vectorHits    向量召回结果（已按相似度排序）
     * @param keywordRecall 关键词召回结果
     * @return 融合后的候选列表（按 RRF 分降序）
     */
    private List<RetrievalCandidate> fuseCandidates(List<VectorSearchService.SearchResult> vectorHits,
                                                    KeywordRecallResult keywordRecall) {
        // 向量通道：ID → 结果
        Map<String, VectorSearchService.SearchResult> vectorById = new LinkedHashMap<>();
        for (VectorSearchService.SearchResult hit : vectorHits) {
            if (hit.getId() != null && !vectorById.containsKey(hit.getId())) {
                vectorById.put(hit.getId(), hit);
            }
        }

        // 关键词通道：ID → (语料, BM25 分)
        Map<String, StoredVectorChunk> keywordById = new LinkedHashMap<>();
        Map<String, Double> bm25ScoreById = new HashMap<>();
        List<String> bm25Keys = new ArrayList<>();
        for (Bm25Rank rank : keywordRecall.ranks) {
            StoredVectorChunk chunk = keywordRecall.chunks.get(rank.getIndex());
            if (chunk.getId() == null || keywordById.containsKey(chunk.getId())) {
                continue;
            }
            keywordById.put(chunk.getId(), chunk);
            bm25ScoreById.put(chunk.getId(), rank.getScore());
            bm25Keys.add(chunk.getId());
        }

        // RRF 只使用名次，因此这里传入的是有序的 ID 列表
        List<String> vectorKeys = new ArrayList<>(vectorById.keySet());
        List<ReciprocalRank> fusedRanks = ReciprocalRankFusion.fuse(
                vectorKeys,
                bm25Keys,
                retrievalProperties.getRerankCandidateLimit(),
                retrievalProperties.getRrfK());

        List<RetrievalCandidate> candidates = new ArrayList<>(fusedRanks.size());
        for (ReciprocalRank fused : fusedRanks) {
            String key = fused.getKey();
            VectorSearchService.SearchResult vectorHit = vectorById.get(key);
            StoredVectorChunk keywordChunk = keywordById.get(key);

            // 优先用向量通道的内容（它带相似度分），关键词独占的候选则用枚举到的分片
            String content = vectorHit != null ? vectorHit.getContent() : keywordChunk.getContent();
            String metadata = vectorHit != null ? vectorHit.getMetadata() : keywordChunk.getMetadata();

            RetrievalCandidate candidate = new RetrievalCandidate();
            candidate.chunkId = key;
            candidate.content = content == null ? "" : content;
            candidate.metadata = metadata;
            candidate.source = extractSource(metadata);
            candidate.vectorRank = fused.getVectorRank();
            candidate.bm25Rank = fused.getBm25Rank();
            candidate.vectorScore = vectorHit == null ? null : (double) vectorHit.getScore();
            candidate.bm25Score = bm25ScoreById.get(key);
            candidate.rrfScore = fused.getScore();
            candidates.add(candidate);
        }

        return candidates;
    }

    /**
     * 调用精排模型并组装最终结果
     *
     * @param query      查询文本
     * @param candidates 融合后的候选
     * @param topK       最终返回条数
     * @return 最终检索结果
     */
    private List<RetrievalHit> rerankAndBuildHits(String query,
                                                  List<RetrievalCandidate> candidates,
                                                  int topK) {
        // 精排已关闭：按 RRF 融合顺序返回，且不伪造精排分
        if (!retrievalProperties.isRerankEnabled()) {
            logger.warn("精排已关闭，本次结果按 RRF 融合顺序返回，rerankScore 与 rerankRank 为空");
            return candidates.stream()
                    .limit(topK)
                    .map(candidate -> buildHit(candidate, null, null))
                    .collect(Collectors.toList());
        }

        List<String> documents = candidates.stream()
                .map(candidate -> candidate.content)
                .collect(Collectors.toList());

        int topN = Math.min(topK, documents.size());
        List<RerankResult> rankings = rerankModel.rerank(query, documents, topN);

        List<RetrievalHit> hits = new ArrayList<>(rankings.size());
        int rerankRank = 1;
        for (RerankResult ranking : rankings) {
            RetrievalCandidate candidate = candidates.get(ranking.getIndex());
            hits.add(buildHit(candidate, rerankRank, ranking.getRelevanceScore()));
            rerankRank++;
        }

        logger.info("混合检索完成：候选 {} 条，精排返回 {} 条", candidates.size(), hits.size());
        return hits;
    }

    /**
     * 组装单条检索结果
     *
     * @param candidate   融合候选
     * @param rerankRank  精排名次，精排关闭时为 null
     * @param rerankScore 精排分，精排关闭时为 null
     * @return 检索结果
     */
    private RetrievalHit buildHit(RetrievalCandidate candidate, Integer rerankRank, Double rerankScore) {
        RetrievalHit hit = new RetrievalHit();
        hit.setChunkId(candidate.chunkId);
        hit.setContent(candidate.content);
        hit.setSource(candidate.source);
        hit.setMetadata(candidate.metadata);
        hit.setVectorRank(candidate.vectorRank);
        hit.setBm25Rank(candidate.bm25Rank);
        hit.setVectorScore(candidate.vectorScore);
        hit.setBm25Score(candidate.bm25Score);
        hit.setRrfScore(candidate.rrfScore);
        hit.setRerankRank(rerankRank);
        hit.setRerankScore(rerankScore);

        // score 始终表示最终排序依据：有精排分用精排分，否则退回融合分（不写进 rerankScore）
        hit.setScore(rerankScore != null ? rerankScore : candidate.rrfScore);
        return hit;
    }

    /**
     * 从元数据 JSON 中解析来源文件路径
     *
     * @param metadata 元数据 JSON 字符串
     * @return 来源路径；解析不到时返回 null
     */
    private String extractSource(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(metadata);
            JsonNode source = node.get(SOURCE_FIELD);
            return source == null || source.isNull() ? null : source.asText();
        } catch (Exception e) {
            // 元数据解析失败不影响检索本身，只丢一个展示字段
            logger.debug("解析分片元数据失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 关键词召回结果：语料 + BM25 排名
     */
    private static class KeywordRecallResult {

        /** 参与打分的语料（下标与 BM25 排名中的 index 对应） */
        private final List<StoredVectorChunk> chunks;

        /** BM25 排名 */
        private final List<Bm25Rank> ranks;

        KeywordRecallResult(List<StoredVectorChunk> chunks, List<Bm25Rank> ranks) {
            this.chunks = chunks;
            this.ranks = ranks;
        }
    }

    /**
     * 融合后的候选（内部使用，比对外结果多了融合所需的中间字段）
     */
    private static class RetrievalCandidate {

        private String chunkId;
        private String content;
        private String metadata;
        private String source;
        private Integer vectorRank;
        private Integer bm25Rank;
        private Double vectorScore;
        private Double bm25Score;
        private Double rrfScore;
    }
}
