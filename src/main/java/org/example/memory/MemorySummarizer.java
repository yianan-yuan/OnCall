package org.example.memory;

/**
 * 记忆摘要生成器
 * <p>
 * 把"调用大模型生成摘要"抽成接口，记忆管理逻辑可脱离大模型单独测试，换摘要模型或策略也不必改核心代码。
 */
public interface MemorySummarizer {

    /**
     * 生成记忆摘要
     *
     * @param prompt 摘要提示词
     * @return 摘要正文（非空）
     * @throws RuntimeException 模型调用失败或返回空摘要时抛出
     */
    String summarize(String prompt);
}
