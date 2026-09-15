package org.example.retrieval;

import lombok.Getter;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）的一条结果
 * <p>
 * 除融合分外还保留两路各自的名次，便于看出某个分片是向量命中、关键词命中，还是两路都命中。
 */
@Getter
public class ReciprocalRank {

    /** 候选唯一标识（本项目使用 Milvus 里的分片 ID） */
    private final String key;

    /** 融合分：Σ 1 / (k + rank)，k 默认 60 */
    private final double score;

    /** 在向量召回结果中的名次（从 1 开始），未命中为 null */
    private final Integer vectorRank;

    /** 在关键词召回结果中的名次（从 1 开始），未命中为 null */
    private final Integer bm25Rank;

    public ReciprocalRank(String key, double score, Integer vectorRank, Integer bm25Rank) {
        this.key = key;
        this.score = score;
        this.vectorRank = vectorRank;
        this.bm25Rank = bm25Rank;
    }

    /**
     * 获取两路中更靠前的名次，用于同分时的确定性排序
     *
     * @return 两路名次中的较小值；理论上至少有一路非空
     */
    public int bestRank() {
        if (vectorRank == null) {
            return bm25Rank == null ? Integer.MAX_VALUE : bm25Rank;
        }
        if (bm25Rank == null) {
            return vectorRank;
        }
        return Math.min(vectorRank, bm25Rank);
    }

    @Override
    public String toString() {
        return "ReciprocalRank{" +
                "key='" + key + '\'' +
                ", score=" + score +
                ", vectorRank=" + vectorRank +
                ", bm25Rank=" + bm25Rank +
                '}';
    }
}
