package org.example.retrieval;

import lombok.Getter;

/**
 * BM25 关键词召回的一条排名结果
 * <p>
 * 只记录语料下标与分数，不直接持有文档内容：打分是在一份临时语料上完成的，语料下标才是回指原文的依据。
 */
@Getter
public class Bm25Rank {

    /** 该结果在传入语料列表中的下标（从 0 开始） */
    private final int index;

    /** BM25L 原始分，只用于同一份语料内的相对排序，不是百分比，也不能跨请求比较 */
    private final double score;

    public Bm25Rank(int index, double score) {
        this.index = index;
        this.score = score;
    }

    @Override
    public String toString() {
        return "Bm25Rank{" +
                "index=" + index +
                ", score=" + score +
                '}';
    }
}
