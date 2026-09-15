package org.example.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BM25 关键词召回器
 * <p>
 * 把"查询文本 + 一批候选文档"变成排好序的文档下标列表，是混合检索中关键词通道的入口：
 * 对查询与文档分词后构建请求内的 BM25L 语料打分，并过滤掉与查询没有词项交集的文档，
 * 因为 BM25L 的 delta 项会给零词频文档一个非零基线分。
 * 同分时按语料原序兜底，保证同一查询两次执行的结果与顺序完全一致。
 */
public final class Bm25DocumentRanker {

    private static final Logger logger = LoggerFactory.getLogger(Bm25DocumentRanker.class);

    /** 关键词通道默认返回的候选数量 */
    public static final int DEFAULT_CANDIDATE_LIMIT = 20;

    private Bm25DocumentRanker() {
        // 工具类，禁止实例化
    }

    /**
     * 使用默认候选数量对文档做 BM25 排序
     *
     * @param query     查询文本（原始文本，内部会分词）
     * @param documents 候选文档原文列表
     * @return 排名结果列表，最多 {@link #DEFAULT_CANDIDATE_LIMIT} 条
     */
    public static List<Bm25Rank> rank(String query, List<String> documents) {
        return rank(query, documents, DEFAULT_CANDIDATE_LIMIT);
    }

    /**
     * 对文档做 BM25 排序
     *
     * @param query     查询文本（原始文本，内部会分词）
     * @param documents 候选文档原文列表
     * @param limit     最多返回多少条候选
     * @return 排名结果列表，index 指向传入的 documents 下标
     */
    public static List<Bm25Rank> rank(String query, List<String> documents, int limit) {
        if (limit < 1 || documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 查询分词：没有词项时直接返回空，避免构建无意义的语料
        List<String> queryTokens = HybridTextTokenizer.tokenize(query);
        if (queryTokens.isEmpty()) {
            logger.debug("BM25 关键词召回跳过：查询分词结果为空");
            return Collections.emptyList();
        }

        // 2. 语料分词
        List<List<String>> corpusTokens = new ArrayList<>(documents.size());
        for (String document : documents) {
            corpusTokens.add(HybridTextTokenizer.tokenize(document));
        }

        // 3. 构建一次性语料并打分
        Bm25LScorer scorer = new Bm25LScorer(corpusTokens);
        double[] scores = scorer.getScores(queryTokens);

        // 4. 只保留与查询词有交集的文档：BM25L 的 delta 项会给"零词频"文档一个非零基线分
        Set<String> queryTokenSet = new HashSet<>(queryTokens);
        List<Bm25Rank> ranks = new ArrayList<>();
        for (int i = 0; i < corpusTokens.size(); i++) {
            if (hasTokenOverlap(queryTokenSet, corpusTokens.get(i))) {
                ranks.add(new Bm25Rank(i, scores[i]));
            }
        }

        // 5. 分数降序；同分时按语料原序升序，保证结果可复现
        ranks.sort((left, right) -> {
            int byScore = Double.compare(right.getScore(), left.getScore());
            return byScore != 0 ? byScore : Integer.compare(left.getIndex(), right.getIndex());
        });

        if (ranks.size() > limit) {
            ranks = new ArrayList<>(ranks.subList(0, limit));
        }

        logger.debug("BM25 关键词召回完成：语料 {} 篇，命中 {} 篇，返回 {} 条",
                corpusTokens.size(), ranks.size(), ranks.size());

        return ranks;
    }

    /**
     * 判断文档词项与查询词项是否有交集
     *
     * @param queryTokenSet 查询词项集合
     * @param documentTokens 文档词项列表
     * @return true 表示至少有一个共同词项
     */
    private static boolean hasTokenOverlap(Set<String> queryTokenSet, List<String> documentTokens) {
        for (String token : documentTokens) {
            if (queryTokenSet.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
