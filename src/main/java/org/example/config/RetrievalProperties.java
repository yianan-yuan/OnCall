package org.example.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 混合检索配置
 * <p>
 * 对应 application.yml 中的 {@code retrieval.*} 配置段，各通道召回条数、RRF 的 k 与精排参数都在这里。
 * 默认值：两路各召回 20 条、RRF 的 k=60、精排后最多返回 5 条。
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "retrieval")
public class RetrievalProperties {

    /** 最终返回给调用方的条数 */
    private int topK = 5;

    /** topK 硬上限：模型在工具参数里要求更大的值时会被截断到这里，避免上下文过长与噪声过多 */
    private int maxTopK = 5;

    /** 向量通道召回的候选数量 */
    private int vectorCandidateLimit = 20;

    /** 关键词（BM25）通道召回的候选数量 */
    private int bm25CandidateLimit = 20;

    /** 融合后进入精排的候选数量 */
    private int rerankCandidateLimit = 20;

    /** RRF 融合的平滑参数 k */
    private int rrfK = 60;

    /** 是否启用精排；关闭它属于显式配置选择（例如本地没有可用的精排服务时用于调试），与运行期失败后自动降级是两回事 */
    private boolean rerankEnabled = true;

    /** 精排模型名称 */
    private String rerankModel = "qwen3.7-text-rerank";

    /** 精排接口地址（阿里云百炼文本排序服务） */
    private String rerankUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    /** 精排请求超时时间（秒） */
    private int rerankTimeoutSeconds = 30;

    /** 精排请求失败后的重试次数（针对限流与服务端错误） */
    private int rerankMaxRetries = 2;

    /** 从 Milvus 枚举分片时的批次大小，分批读取避免一次性拉取大量语料造成内存峰值 */
    private long chunkEnumerationBatchSize = 500L;

    /** 从 Milvus 枚举分片的最大条数，知识库过大时限制枚举规模，避免内存吃满、响应时间失控 */
    private long chunkEnumerationLimit = 10000L;

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public void setMaxTopK(int maxTopK) {
        this.maxTopK = maxTopK;
    }

    public void setVectorCandidateLimit(int vectorCandidateLimit) {
        this.vectorCandidateLimit = vectorCandidateLimit;
    }

    public void setBm25CandidateLimit(int bm25CandidateLimit) {
        this.bm25CandidateLimit = bm25CandidateLimit;
    }

    public void setRerankCandidateLimit(int rerankCandidateLimit) {
        this.rerankCandidateLimit = rerankCandidateLimit;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public void setRerankEnabled(boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
    }

    public void setRerankModel(String rerankModel) {
        this.rerankModel = rerankModel;
    }

    public void setRerankUrl(String rerankUrl) {
        this.rerankUrl = rerankUrl;
    }

    public void setRerankTimeoutSeconds(int rerankTimeoutSeconds) {
        this.rerankTimeoutSeconds = rerankTimeoutSeconds;
    }

    public void setRerankMaxRetries(int rerankMaxRetries) {
        this.rerankMaxRetries = rerankMaxRetries;
    }

    public void setChunkEnumerationBatchSize(long chunkEnumerationBatchSize) {
        this.chunkEnumerationBatchSize = chunkEnumerationBatchSize;
    }

    public void setChunkEnumerationLimit(long chunkEnumerationLimit) {
        this.chunkEnumerationLimit = chunkEnumerationLimit;
    }

    /**
     * 获取生效的返回条数：取配置值与硬上限中的较小值，并保证至少为 1
     *
     * @return 实际使用的 topK
     */
    public int effectiveTopK() {
        int limit = Math.min(topK, maxTopK);
        return Math.max(limit, 1);
    }
}
