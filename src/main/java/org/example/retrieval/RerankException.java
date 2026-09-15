package org.example.retrieval;

/**
 * 精排调用异常
 * <p>
 * 只携带对调用方安全的提示信息，不暴露上游响应内容，避免把供应商的错误细节泄露到工具结果或前端。
 * 混合检索任一环节失败都不降级为单路，因此该异常会向上冒泡成统一的系统错误。
 */
public class RerankException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RerankException(String message) {
        super(message);
    }

    public RerankException(String message, Throwable cause) {
        super(message, cause);
    }
}
