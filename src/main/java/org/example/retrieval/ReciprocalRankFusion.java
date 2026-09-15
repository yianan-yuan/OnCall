package org.example.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）
 * <p>
 * 混合检索中向量召回与关键词召回两路结果的合并算法，只用名次不用原始分数，避免两路分量纲不同、无法直接加权。
 * 公式：score(candidate) = Σ 1 / (k + rank)，k 默认 60；k 越大，名次差异的影响越小。
 * 同分时依次按"两路中更靠前的名次"、"候选 ID 字典序"排序，保证同一查询每次执行的结果集合与顺序完全一致。
 */
public final class ReciprocalRankFusion {

    /** RRF 默认的平滑参数 k */
    public static final int DEFAULT_K = 60;

    /** 向量来源的标识 */
    private static final String SOURCE_VECTOR = "vector";

    /** 关键词来源的标识 */
    private static final String SOURCE_BM25 = "bm25";

    private ReciprocalRankFusion() {
        // 工具类，禁止实例化
    }

    /**
     * 使用默认 k 融合两路排名
     *
     * @param vectorKeys 向量召回结果，按名次从优到劣排列的候选 ID
     * @param bm25Keys   关键词召回结果，按名次从优到劣排列的候选 ID
     * @param limit      最多返回多少条融合结果
     * @return 融合后的排名列表
     */
    public static List<ReciprocalRank> fuse(List<String> vectorKeys, List<String> bm25Keys, int limit) {
        return fuse(vectorKeys, bm25Keys, limit, DEFAULT_K);
    }

    /**
     * 融合两路排名
     *
     * @param vectorKeys 向量召回结果，按名次从优到劣排列的候选 ID
     * @param bm25Keys   关键词召回结果，按名次从优到劣排列的候选 ID
     * @param limit      最多返回多少条融合结果
     * @param k          平滑参数，必须大于 0
     * @return 融合后的排名列表
     * @throws IllegalArgumentException 当 k 小于 1 时抛出
     */
    public static List<ReciprocalRank> fuse(List<String> vectorKeys, List<String> bm25Keys, int limit, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("RRF 的 k 必须大于 0，当前值: " + k);
        }

        // 记录每个候选在两路中的名次：候选 ID -> (来源 -> 名次)，用 LinkedHashMap 保持首次出现的顺序
        Map<String, Map<String, Integer>> positions = new LinkedHashMap<>();
        collectPositions(positions, SOURCE_VECTOR, vectorKeys);
        collectPositions(positions, SOURCE_BM25, bm25Keys);

        // 计算融合分
        List<ReciprocalRank> fused = new ArrayList<>(positions.size());
        for (Map.Entry<String, Map<String, Integer>> entry : positions.entrySet()) {
            Map<String, Integer> sourcePositions = entry.getValue();

            double score = 0d;
            for (Integer rank : sourcePositions.values()) {
                score += 1d / (k + rank);
            }

            fused.add(new ReciprocalRank(
                    entry.getKey(),
                    score,
                    sourcePositions.get(SOURCE_VECTOR),
                    sourcePositions.get(SOURCE_BM25)));
        }

        // 确定性排序：融合分降序 -> 两路更靠前的名次升序 -> 候选 ID 字典序升序
        Comparator<ReciprocalRank> comparator = Comparator
                .comparingDouble(ReciprocalRank::getScore).reversed()
                .thenComparingInt(ReciprocalRank::bestRank)
                .thenComparing(ReciprocalRank::getKey);
        fused.sort(comparator);

        if (limit < 0) {
            limit = 0;
        }
        if (fused.size() > limit) {
            return new ArrayList<>(fused.subList(0, limit));
        }
        return fused;
    }

    /**
     * 收集某一路来源的名次
     * <p>
     * 同一路里重复出现的候选 ID 只记录第一次出现的名次，避免重复计分。
     *
     * @param positions 名次收集容器
     * @param source    来源标识
     * @param keys      该路的候选 ID 列表，按名次排列
     */
    private static void collectPositions(Map<String, Map<String, Integer>> positions,
                                         String source,
                                         List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }

        int rank = 1;
        for (String key : keys) {
            if (key == null) {
                rank++;
                continue;
            }

            Map<String, Integer> sourcePositions =
                    positions.computeIfAbsent(key, unused -> new LinkedHashMap<>());

            if (!sourcePositions.containsKey(source)) {
                sourcePositions.put(source, rank);
            }

            rank++;
        }
    }
}
