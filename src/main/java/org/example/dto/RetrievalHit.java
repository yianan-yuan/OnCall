package org.example.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 混合检索返回的一条命中结果
 * <p>
 * 保留向量召回 / 关键词召回 / 精排三阶段的名次与分数：既能看出某条结果是语义命中还是精确关键词命中，
 * 也便于在召回效果不好时定位是哪一路出的问题。
 * {@code score} 始终等于精排分，是最终排序依据，不允许用向量分、BM25 分或融合分冒充。
 */
@Setter
@Getter
public class RetrievalHit {

    /** 分片 ID（Milvus 主键），也是引用与结果的对应标识 */
    private String chunkId;

    /** 分片正文 */
    private String content;

    /** 来源文档路径（从元数据的 _source 字段解析，解析不到时为 null） */
    private String source;

    /** 分片元数据（JSON 字符串） */
    private String metadata;

    /** 最终分数，等于精排分 */
    private double score;

    /** 向量召回名次（从 1 开始），该路未命中时为 null */
    private Integer vectorRank;

    /** 关键词召回名次（从 1 开始），该路未命中时为 null */
    private Integer bm25Rank;

    /** 精排名次（从 1 开始） */
    private Integer rerankRank;

    /** 向量相似度原始分（L2 距离，越小越相似），该路未命中时为 null */
    private Double vectorScore;

    /** BM25 原始分（只用于同一次检索内的相对比较），该路未命中时为 null */
    private Double bm25Score;

    /** RRF 融合分 */
    private Double rrfScore;

    /** 精排相关度分数（0~1） */
    private Double rerankScore;

    @Override
    public String toString() {
        return "RetrievalHit{" +
                "chunkId='" + chunkId + '\'' +
                ", score=" + score +
                ", vectorRank=" + vectorRank +
                ", bm25Rank=" + bm25Rank +
                ", rerankRank=" + rerankRank +
                ", contentLength=" + (content == null ? 0 : content.length()) +
                '}';
    }
}
