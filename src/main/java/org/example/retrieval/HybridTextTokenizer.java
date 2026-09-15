package org.example.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中英文混合分词器
 * <p>
 * 用于混合检索中的 BM25 关键词召回：英文数字组合（用 . _ : / - 连接）整体成词并转小写，
 * 连续汉字切成单字并额外生成相邻两字的二元词项，其余字符作为分隔符丢弃。
 * 这样既能抓住错误码、API 路径、服务名等精确标识符，也能匹配"超时""连接池"这类短语。
 * 有意不引 jieba/HanLP 之类的分词库：词典一变词项集合就变，检索排序会跟着漂，也不好复现。
 * 分词规则不可随意调整，改动它会改变 BM25 分数与候选排序。
 */
public final class HybridTextTokenizer {

    /** 词项匹配正则：英文数字组合（允许 . _ : / - 作内部连接符）或连续汉字 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "[A-Za-z0-9]+(?:[._:/-][A-Za-z0-9]+)*|[\\u3400-\\u4dbf\\u4e00-\\u9fff]+");

    /** 汉字码点下界（扩展 A 区起始） */
    private static final char CHINESE_RANGE_START = '\u3400';

    /** 汉字码点上界（统一表意文字结束） */
    private static final char CHINESE_RANGE_END = '\u9fff';

    private HybridTextTokenizer() {
        // 工具类，禁止实例化
    }

    /**
     * 对文本分词
     *
     * @param text 待分词文本，允许为 null（返回空列表）
     * @return 词项列表，按出现顺序排列；同一词项可能重复出现（BM25 需要词频，不能去重）
     */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();

        if (text == null || text.isEmpty()) {
            return tokens;
        }

        Matcher matcher = TOKEN_PATTERN.matcher(text);

        while (matcher.find()) {
            // 统一转小写（使用 Locale.ROOT 避免土耳其语等区域的 i/I 转换差异）
            String value = matcher.group().toLowerCase(Locale.ROOT);

            if (isChineseSegment(value)) {
                appendChineseTokens(tokens, value);
            } else {
                tokens.add(value);
            }
        }

        return tokens;
    }

    /**
     * 把一段连续汉字切成"单字 + 相邻两字"的词项
     *
     * @param tokens 结果收集列表
     * @param segment 连续汉字片段
     */
    private static void appendChineseTokens(List<String> tokens, String segment) {
        int length = segment.length();

        // 1. 先加入全部单字
        for (int i = 0; i < length; i++) {
            tokens.add(String.valueOf(segment.charAt(i)));
        }

        // 2. 再加入相邻两字组成的二元词项（长度为 1 时不会产生）
        for (int i = 0; i + 1 < length; i++) {
            tokens.add(segment.substring(i, i + 2));
        }
    }

    /**
     * 判断某个词项是否为纯汉字片段（正则已保证整段都是汉字，只看首字符是否落在汉字区间即可）
     *
     * @param value 已转小写的词项
     * @return true 表示这是汉字片段，需要按单字 + 二元词切分
     */
    private static boolean isChineseSegment(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return first >= CHINESE_RANGE_START && first <= CHINESE_RANGE_END;
    }
}
