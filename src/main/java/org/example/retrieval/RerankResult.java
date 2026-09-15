package org.example.retrieval;

import lombok.Getter;

/**
 * 精排（rerank）返回的一条结果
 * <p>
 * {@code index} 是输入文档列表中的下标，调用方要用它把分数映射回原始候选，不能假设返回顺序等于输入顺序。
 */
@Getter
public class RerankResult {

    /** 候选在输入文档列表中的下标（从 0 开始） */
    private final int index;

    /** 相关度分数，取值范围 0 ~ 1，越大越相关 */
    private final double relevanceScore;

    public RerankResult(int index, double relevanceScore) {
        this.index = index;
        this.relevanceScore = relevanceScore;
    }

    @Override
    public String toString() {
        return "RerankResult{" +
                "index=" + index +
                ", relevanceScore=" + relevanceScore +
                '}';
    }
}
