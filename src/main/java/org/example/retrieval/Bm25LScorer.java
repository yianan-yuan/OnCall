package org.example.retrieval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25L 打分器
 * <p>
 * BM25 是关键词相关性打分算法：词在当前文档出现越多、在整个语料出现越少，文档越相关。
 * 用于混合检索的关键词召回通道。
 * <p>
 * 选用 BM25L 而非 Okapi：Okapi 的 IDF 在高频词上会变为负数，小语料下会出现
 * "匹配更全的文档排名更低"的问题；BM25L 的 IDF 为 {@code log(N + 1) - log(df + 0.5)}，恒为正。
 * <p>
 * 打分公式：
 * <pre>
 *     ctd   = tf / (1 - b + b * dl / avgdl)
 *     score = Σ idf(q) * (k1 + 1) * (ctd + delta) / (k1 + ctd + delta)
 * </pre>
 * 注意：文档不含查询词时 ctd 为 0，但 {@code (0 + delta) / (k1 + 0 + delta)} 不为 0，
 * 因此候选过滤需由调用方按"词项交集"完成，见 {@link Bm25DocumentRanker}。
 */
public final class Bm25LScorer {

    /** 词频饱和参数（与 rank_bm25 默认值一致） */
    public static final double DEFAULT_K1 = 1.5;

    /** 文档长度归一化参数（与 rank_bm25 默认值一致） */
    public static final double DEFAULT_B = 0.75;

    /** BM25L 特有的词频下界补偿项（与 rank_bm25 默认值一致） */
    public static final double DEFAULT_DELTA = 0.5;

    /** k1 参数 */
    private final double k1;

    /** b 参数 */
    private final double b;

    /** delta 参数 */
    private final double delta;

    /** 语料中的文档数量 */
    private final int corpusSize;

    /** 平均文档长度（按词项个数计算） */
    private final double averageDocumentLength;

    /** 每篇文档的词频表：文档下标 -> (词项 -> 出现次数) */
    private final List<Map<String, Integer>> documentTermFrequencies;

    /** 每篇文档的长度（词项个数，含重复） */
    private final int[] documentLengths;

    /** 词项 -> IDF 值，只包含语料中出现过的词项 */
    private final Map<String, Double> idf;

    /**
     * 使用默认参数构造打分器
     *
     * @param corpus 语料，每个元素是一篇已分词的文档
     */
    public Bm25LScorer(List<List<String>> corpus) {
        this(corpus, DEFAULT_K1, DEFAULT_B, DEFAULT_DELTA);
    }

    /**
     * 构造打分器
     * <p>
     * 构造过程会遍历语料统计词频、文档频率与平均文档长度，时间复杂度 O(语料总词数)。
     *
     * @param corpus 语料，每个元素是一篇已分词的文档
     * @param k1     词频饱和参数
     * @param b      文档长度归一化参数
     * @param delta  BM25L 的词频下界补偿项
     */
    public Bm25LScorer(List<List<String>> corpus, double k1, double b, double delta) {
        this.k1 = k1;
        this.b = b;
        this.delta = delta;

        List<List<String>> safeCorpus = corpus == null ? Collections.emptyList() : corpus;
        this.corpusSize = safeCorpus.size();
        this.documentTermFrequencies = new ArrayList<>(corpusSize);
        this.documentLengths = new int[corpusSize];

        // 文档频率：词项 -> 包含该词项的文档数量（用于计算 IDF）
        Map<String, Integer> documentFrequency = new HashMap<>();
        long totalTokenCount = 0;

        for (int i = 0; i < corpusSize; i++) {
            List<String> document = safeCorpus.get(i);
            List<String> safeDocument = document == null ? Collections.emptyList() : document;

            int length = safeDocument.size();
            documentLengths[i] = length;
            totalTokenCount += length;

            // 统计当前文档的词频
            Map<String, Integer> termFrequency = new HashMap<>();
            for (String term : safeDocument) {
                termFrequency.merge(term, 1, Integer::sum);
            }
            documentTermFrequencies.add(termFrequency);

            // 同一个词在一篇文档里只算一次文档频率
            for (String term : termFrequency.keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        this.averageDocumentLength = corpusSize == 0 ? 0d : (double) totalTokenCount / corpusSize;

        // 计算 IDF：BM25L 使用 log(N + 1) - log(df + 0.5)，结果恒为正
        Map<String, Double> idfValues = new HashMap<>();
        for (Map.Entry<String, Integer> entry : documentFrequency.entrySet()) {
            double value = Math.log(corpusSize + 1d) - Math.log(entry.getValue() + 0.5d);
            idfValues.put(entry.getKey(), value);
        }
        this.idf = Collections.unmodifiableMap(idfValues);
    }

    /**
     * 计算查询词对每篇文档的打分
     *
     * @param query 已分词的查询词列表，允许为 null 或空
     * @return 与语料等长的分数数组，下标与传入的语料顺序一一对应
     */
    public double[] getScores(List<String> query) {
        double[] scores = new double[corpusSize];

        if (query == null || query.isEmpty() || corpusSize == 0) {
            return scores;
        }

        // 防御：所有文档都是空文档时平均长度为 0，直接用会导致 0/0 产生 NaN。
        // 这种情况下"词项交集"过滤会把所有文档排除掉，这里的兜底只是为了不产生 NaN 污染。
        double safeAverageLength = averageDocumentLength > 0 ? averageDocumentLength : 1d;

        for (String term : query) {
            Double termIdf = idf.get(term);

            // 查询词不在语料中时贡献 0（与参考实现 idf.get(q) or 0 的语义一致）
            if (termIdf == null) {
                continue;
            }

            for (int i = 0; i < corpusSize; i++) {
                int termFrequency = documentTermFrequencies.get(i).getOrDefault(term, 0);
                double documentLength = documentLengths[i];

                // ctd：经过文档长度归一化后的词频
                double ctd = termFrequency / (1 - b + b * documentLength / safeAverageLength);

                scores[i] += termIdf * (k1 + 1) * (ctd + delta) / (k1 + ctd + delta);
            }
        }

        return scores;
    }

    /**
     * 获取语料中的文档数量
     *
     * @return 文档数量
     */
    public int getCorpusSize() {
        return corpusSize;
    }

    /**
     * 获取平均文档长度
     *
     * @return 平均文档长度（词项个数）
     */
    public double getAverageDocumentLength() {
        return averageDocumentLength;
    }
}
