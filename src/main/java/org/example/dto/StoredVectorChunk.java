package org.example.dto;

import lombok.Getter;

/**
 * 从 Milvus 枚举出来的分片（只含标量字段，不含向量）
 * <p>
 * 关键词召回需要把候选语料整体读到内存统计词频，因此只读取文字与元数据，不读取体量大好几个数量级的向量字段。
 */
@Getter
public class StoredVectorChunk {

    /** 分片 ID（Milvus 主键） */
    private final String id;

    /** 分片正文 */
    private final String content;

    /** 分片元数据（JSON 字符串，包含来源文件、分片序号等） */
    private final String metadata;

    public StoredVectorChunk(String id, String content, String metadata) {
        this.id = id;
        this.content = content;
        this.metadata = metadata;
    }

    @Override
    public String toString() {
        return "StoredVectorChunk{" +
                "id='" + id + '\'' +
                ", contentLength=" + (content == null ? 0 : content.length()) +
                '}';
    }
}
