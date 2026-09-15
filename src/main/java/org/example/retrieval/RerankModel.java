package org.example.retrieval;

import java.util.List;

/**
 * 精排模型边界接口
 * <p>
 * 混合检索流水线的最后一步：RRF 融合出候选之后，由精排模型对"查询 - 文档"逐条打分。
 * 因为向量相似度和 BM25 都只是粗排信号，判断不了语义上是否真正回答了问题。
 * 抽成接口是为了让检索服务不依赖具体模型厂商，单元测试里也可以注入假实现。
 */
public interface RerankModel {

    /**
     * 对候选文档按相关度重新排序
     *
     * @param query     用户查询文本
     * @param documents 候选文档内容列表，顺序即输入下标
     * @param topN      需要返回的结果条数，必须大于 0 且不超过文档数量
     * @return 按相关度降序排列的结果，最多 topN 条
     * @throws RerankException 调用失败或返回结果不合法时抛出（消息对调用方安全，不含上游响应细节）
     */
    List<RerankResult> rerank(String query, List<String> documents, int topN);
}
