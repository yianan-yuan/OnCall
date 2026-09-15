package org.example.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BM25 关键词召回单元测试
 * <p>
 * 用例移植自 Python 参考实现，重点锁死三条容易出错的语义：
 * <ol>
 *     <li>只有与查询词有交集的文档才能成为候选（BM25L 的 delta 项会给零词频文档基线分）；</li>
 *     <li>IDF 必须恒为正，小语料下不会出现负分；</li>
 *     <li>排序是"分数降序 + 语料原序"的确定性顺序。</li>
 * </ol>
 */
class Bm25DocumentRankerTest {

    @Test
    @DisplayName("精确关键词命中排在前面，无词项交集的文档被排除")
    void shouldReturnExactKeywordMatchesAndExcludeNoOverlap() {
        List<Bm25Rank> ranks = Bm25DocumentRanker.rank(
                "E_CONN_RESET api-gateway",
                Arrays.asList(
                        "通用网络故障排查说明",
                        "api-gateway failed with E_CONN_RESET and retried",
                        "api-worker request succeeded"),
                20);

        // 只有第 2 篇同时命中了 api-gateway 与 e_conn_reset；第一篇与第三篇不应该出现在候选里
        assertThat(ranks).hasSize(1);
        assertThat(ranks.get(0).getIndex()).isEqualTo(1);
        assertThat(ranks.get(0).getScore()).isGreaterThan(0d);
    }

    @Test
    @DisplayName("小语料下精确标识符得到正分")
    void shouldUsePositiveIdfForExactIdentifierInSmallCorpus() {
        List<Bm25Rank> ranks = Bm25DocumentRanker.rank(
                "HYBRID-RRF-74291",
                Arrays.asList(
                        "故障码 HYBRID-RRF-74291 表示蓝色队列租约过期。",
                        "通用服务健康检查说明。"));

        assertThat(ranks).hasSize(1);
        assertThat(ranks.get(0).getIndex()).isEqualTo(0);
        assertThat(ranks.get(0).getScore()).isGreaterThan(0d);
    }

    @Test
    @DisplayName("高频词不会产生负分，且匹配更完整的文档排名更高")
    void shouldNeverProduceNegativeScoresForHighFrequencyTerms() {
        List<Bm25Rank> ranks = Bm25DocumentRanker.rank(
                "服务 超时",
                Arrays.asList(
                        "服务发生超时，需要检查连接池。",
                        "服务运行正常。",
                        "服务健康检查通过。"));

        // 三篇文档都含"服务"，因此都应成为候选
        assertThat(ranks).extracting(Bm25Rank::getIndex).containsExactly(0, 1, 2);

        // 关键断言：BM25L 的 IDF 恒为正，因此不会出现负分
        assertThat(ranks).allSatisfy(rank -> assertThat(rank.getScore()).isGreaterThanOrEqualTo(0d));

        // 同时命中"服务"和"超时"的文档必须排在只命中"服务"的文档前面
        assertThat(ranks.get(0).getScore()).isGreaterThan(ranks.get(1).getScore());
    }

    @Test
    @DisplayName("查询为空或语料为空时返回空列表")
    void shouldReturnEmptyWhenQueryOrCorpusIsEmpty() {
        assertThat(Bm25DocumentRanker.rank("", Arrays.asList("任意内容"))).isEmpty();
        assertThat(Bm25DocumentRanker.rank(null, Arrays.asList("任意内容"))).isEmpty();
        assertThat(Bm25DocumentRanker.rank("超时", Collections.emptyList())).isEmpty();
        assertThat(Bm25DocumentRanker.rank("超时", Arrays.asList("内容"), 0)).isEmpty();
    }

    @Test
    @DisplayName("候选数量受 limit 限制，且按分数降序")
    void shouldRespectCandidateLimit() {
        List<Bm25Rank> ranks = Bm25DocumentRanker.rank(
                "超时",
                Arrays.asList(
                        "超时超时超时，连接池耗尽",
                        "超时问题排查",
                        "疑似超时"),
                2);

        assertThat(ranks).hasSize(2);
        assertThat(ranks.get(0).getScore()).isGreaterThanOrEqualTo(ranks.get(1).getScore());
        // 词频最高的文档应排在第一
        assertThat(ranks.get(0).getIndex()).isEqualTo(0);
    }
}
