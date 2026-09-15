package org.example.retrieval;

/**
 * 知识检索异常
 * <p>
 * 混合检索的各环节（向量化、向量搜索、分片枚举、BM25 排序、精排）任一失败都抛这个异常，由上层统一转成系统暂时不可用；
 * 不降级为单路检索，是为了避免静默改变召回分布，让同一问题在不同时间得到质量不同的答案。
 */
public class RetrievalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RetrievalException(String message) {
        super(message);
    }

    public RetrievalException(String message, Throwable cause) {
        super(message, cause);
    }
}
