package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.orm.iterator.QueryIterator;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.QueryIteratorParam;
import io.milvus.response.QueryResultsWrapper;
import org.example.config.RetrievalProperties;
import org.example.constant.MilvusConstants;
import org.example.dto.StoredVectorChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 向量分片枚举服务
 * <p>
 * 为关键词召回（BM25）提供候选语料：BM25 基于整个语料统计词频与文档频率，必须读全才能算分。
 * 因此只读标量字段（id / content / metadata）、不读 vector（向量数据量比正文大几个数量级），
 * 并分批迭代读取以避免内存峰值。
 */
@Service
public class VectorChunkService {

    private static final Logger logger = LoggerFactory.getLogger(VectorChunkService.class);

    /** 枚举时读取的字段（刻意不包含 vector） */
    private static final List<String> OUT_FIELDS = Arrays.asList("id", "content", "metadata");

    /** "匹配全部"的过滤表达式：主键非空即视为全部记录 */
    private static final String MATCH_ALL_EXPRESSION = "id != \"\"";

    /** Milvus 返回"集合已加载"的状态码，不算错误 */
    private static final int ALREADY_LOADED_STATUS = 65535;

    private final MilvusServiceClient milvusClient;

    private final RetrievalProperties retrievalProperties;

    public VectorChunkService(MilvusServiceClient milvusClient, RetrievalProperties retrievalProperties) {
        this.milvusClient = milvusClient;
        this.retrievalProperties = retrievalProperties;
    }

    /**
     * 枚举集合中的全部分片（只含标量字段）
     *
     * @return 分片列表，最多 {@code retrieval.chunk-enumeration-limit} 条
     */
    public List<StoredVectorChunk> listAllChunks() {
        List<StoredVectorChunk> chunks = new ArrayList<>();

        // 迭代器要求集合处于已加载状态
        ensureCollectionLoaded();

        QueryIterator iterator = null;
        try {
            QueryIteratorParam param = QueryIteratorParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(MATCH_ALL_EXPRESSION)
                    .withOutFields(OUT_FIELDS)
                    .withBatchSize(retrievalProperties.getChunkEnumerationBatchSize())
                    .withLimit(retrievalProperties.getChunkEnumerationLimit())
                    .build();

            R<QueryIterator> response = milvusClient.queryIterator(param);
            if (response.getStatus() != 0) {
                throw new RuntimeException("枚举分片失败: " + response.getMessage());
            }

            iterator = response.getData();

            // 迭代器没有 hasNext()，约定是"next() 返回空集合表示结束"
            while (true) {
                List<QueryResultsWrapper.RowRecord> records = iterator.next();
                if (records == null || records.isEmpty()) {
                    break;
                }

                for (QueryResultsWrapper.RowRecord record : records) {
                    StoredVectorChunk chunk = toStoredChunk(record);
                    if (chunk != null) {
                        chunks.add(chunk);
                    }
                }
            }

            logger.info("分片枚举完成：共 {} 条（只读取标量字段，未读取向量）", chunks.size());
            return chunks;

        } catch (Exception e) {
            logger.error("分片枚举失败", e);
            // 关键词召回依赖完整语料，枚举失败必须向上抛出，由检索服务统一转成系统错误
            throw new RuntimeException("分片枚举失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(iterator);
        }
    }

    /**
     * 确保集合已加载（迭代查询需要）
     */
    private void ensureCollectionLoaded() {
        R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                        .build());

        if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != ALREADY_LOADED_STATUS) {
            throw new RuntimeException("加载 collection 失败: " + loadResponse.getMessage());
        }
    }

    /**
     * 把 Milvus 行记录转换成业务对象
     *
     * @param record Milvus 行记录
     * @return 分片对象；缺少主键时返回 null（该行会被跳过）
     */
    private StoredVectorChunk toStoredChunk(QueryResultsWrapper.RowRecord record) {
        Object idValue = record.get("id");
        if (idValue == null) {
            logger.warn("枚举到一条缺少主键的记录，已跳过");
            return null;
        }

        Object contentValue = record.get("content");
        Object metadataValue = record.get("metadata");

        return new StoredVectorChunk(
                String.valueOf(idValue),
                contentValue == null ? "" : String.valueOf(contentValue),
                metadataValue == null ? null : metadataValue.toString());
    }

    /**
     * 静默关闭迭代器，避免释放失败干扰主流程
     *
     * @param iterator Milvus 迭代器，可能为 null
     */
    private void closeQuietly(QueryIterator iterator) {
        if (iterator == null) {
            return;
        }
        try {
            iterator.close();
        } catch (Exception e) {
            logger.warn("关闭 Milvus 迭代器失败: {}", e.getMessage());
        }
    }
}
