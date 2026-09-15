package org.example.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中英文混合分词器单元测试
 * <p>
 * 用例直接移植自 Python 参考实现（rank_bm25 混合检索）的测试，
 * 用来锁死分词行为：一旦有人调整了正则或中文切分规则，这里会立刻失败——
 * 分词变化会连带改变 BM25 分数与召回顺序，属于必须被测试拦住的回归。
 */
class HybridTextTokenizerTest {

    @Test
    @DisplayName("英文标识符整体保留，中文生成单字与二元词")
    void shouldPreserveOperationalTermsAndChineseBigrams() {
        List<String> tokens = HybridTextTokenizer.tokenize(
                "API 超时错误 InternalError.Algo.InvalidParameter /v1/chat");

        // 英文统一转小写
        assertThat(tokens).contains("api");

        // 中文既要能按单字匹配，也要能按两字短语匹配
        assertThat(tokens).contains("超时", "错误");

        // 带点的长错误码不能被切碎，否则精确标识符检索会失效
        assertThat(tokens).contains("internalerror.algo.invalidparameter");

        // 斜杠连接的 API 路径要整体保留
        assertThat(tokens).contains("v1/chat");
    }

    @Test
    @DisplayName("中文同时产生单字与相邻两字词项")
    void shouldProduceUnigramsAndBigramsForChinese() {
        List<String> tokens = HybridTextTokenizer.tokenize("超时");

        assertThat(tokens).containsExactly("超", "时", "超时");
    }

    @Test
    @DisplayName("不去重：BM25 依赖词频")
    void shouldKeepDuplicateTokens() {
        List<String> tokens = HybridTextTokenizer.tokenize("timeout timeout");

        assertThat(tokens).containsExactly("timeout", "timeout");
    }

    @Test
    @DisplayName("下划线、连字符、冒号连接的标识符保持完整并转小写")
    void shouldKeepConnectorJoinedIdentifiers() {
        List<String> tokens = HybridTextTokenizer.tokenize("E_CONN_RESET hybrid-rrf kafka:9092");

        assertThat(tokens).contains("e_conn_reset", "hybrid-rrf", "kafka:9092");
    }

    @Test
    @DisplayName("空输入返回空列表")
    void shouldReturnEmptyForBlankInput() {
        assertThat(HybridTextTokenizer.tokenize(null)).isEmpty();
        assertThat(HybridTextTokenizer.tokenize("")).isEmpty();
        assertThat(HybridTextTokenizer.tokenize("   \n\t ")).isEmpty();
    }
}
