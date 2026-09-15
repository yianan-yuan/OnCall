package org.example.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * RRF 融合单元测试
 * <p>
 * 用例移植自 Python 参考实现，重点锁死：
 * <ol>
 *     <li>融合分只依赖名次：score = 1/(k+向量名次) + 1/(k+关键词名次)，k=60；</li>
 *     <li>两路都命中的候选排在只命中一路的候选前面；</li>
 *     <li>同分时按"更靠前的名次"、再按候选 ID 排序，保证结果可复现。</li>
 * </ol>
 */
class ReciprocalRankFusionTest {

    @Test
    @DisplayName("k=60 融合两路，两路都命中的候选排第一，顺序确定")
    void shouldFuseSharedCandidatesWithK60AndDeterministicOrder() {
        List<ReciprocalRank> fused = ReciprocalRankFusion.fuse(
                Arrays.asList("semantic", "shared", "vector-only"),
                Arrays.asList("shared", "exact", "semantic"),
                20);

        assertThat(fused).extracting(ReciprocalRank::getKey)
                .containsExactly("shared", "semantic", "exact", "vector-only");

        // shared：向量第 2 名、关键词第 1 名
        assertThat(fused.get(0).getScore()).isCloseTo(1d / 62 + 1d / 61, within(1e-12));
        assertThat(fused.get(0).getVectorRank()).isEqualTo(2);
        assertThat(fused.get(0).getBm25Rank()).isEqualTo(1);
    }

    @Test
    @DisplayName("只命中一路的候选保留另一路为空名次")
    void shouldKeepNullRankForSingleSourceCandidate() {
        List<ReciprocalRank> fused = ReciprocalRankFusion.fuse(
                Collections.singletonList("vector-only"),
                Collections.singletonList("bm25-only"),
                20);

        ReciprocalRank vectorOnly = fused.stream()
                .filter(item -> "vector-only".equals(item.getKey()))
                .findFirst()
                .orElseThrow();
        assertThat(vectorOnly.getVectorRank()).isEqualTo(1);
        assertThat(vectorOnly.getBm25Rank()).isNull();

        ReciprocalRank bm25Only = fused.stream()
                .filter(item -> "bm25-only".equals(item.getKey()))
                .findFirst()
                .orElseThrow();
        assertThat(bm25Only.getVectorRank()).isNull();
        assertThat(bm25Only.getBm25Rank()).isEqualTo(1);
    }

    @Test
    @DisplayName("k 必须大于 0")
    void shouldRejectInvalidK() {
        assertThatThrownBy(() -> ReciprocalRankFusion.fuse(
                Collections.emptyList(), Collections.emptyList(), 20, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须大于 0");
    }

    @Test
    @DisplayName("同一路内重复出现的候选只计一次名次")
    void shouldDeduplicateWithinSameSource() {
        List<ReciprocalRank> fused = ReciprocalRankFusion.fuse(
                Arrays.asList("dup", "dup", "other"),
                Collections.emptyList(),
                20);

        ReciprocalRank duplicated = fused.stream()
                .filter(item -> "dup".equals(item.getKey()))
                .findFirst()
                .orElseThrow();

        // 只保留首次出现的名次（第 1 名），不会被后面的重复项覆盖成第 2 名
        assertThat(duplicated.getVectorRank()).isEqualTo(1);
        assertThat(duplicated.getScore()).isCloseTo(1d / 61, within(1e-12));
    }

    @Test
    @DisplayName("融合结果受 limit 限制")
    void shouldRespectLimit() {
        List<ReciprocalRank> fused = ReciprocalRankFusion.fuse(
                Arrays.asList("a", "b", "c"),
                Arrays.asList("d", "e"),
                3);

        assertThat(fused).hasSize(3);
    }

    @Test
    @DisplayName("两路都为空时返回空列表")
    void shouldReturnEmptyWhenBothSourcesAreEmpty() {
        assertThat(ReciprocalRankFusion.fuse(
                Collections.emptyList(), Collections.emptyList(), 20)).isEmpty();
    }
}
