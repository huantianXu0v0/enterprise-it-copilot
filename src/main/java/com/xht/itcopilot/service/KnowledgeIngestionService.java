package com.xht.itcopilot.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.xht.itcopilot.entity.ChunkPreview;
import com.xht.itcopilot.entity.KnowledgeBaseDO;
import com.xht.itcopilot.entity.KnowledgeDocumentDO;
import com.xht.itcopilot.mapper.KnowledgeBaseMapper;
import com.xht.itcopilot.mapper.KnowledgeDocumentMapper;
import com.xht.itcopilot.mapper.KnowledgeIngestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestionService {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeIngestionMapper ingestionMapper;

    private final KnowledgeDocumentService documentService;
    private final EmbeddingService embeddingService;
    private final PlatformTransactionManager transactionManager;

    // 当前单模型的项目内标识，与知识库 embedding_model 对应。
    @Value("${ai.embedding.id:qwen-emb-8b}")
    private String embeddingId;

    @Value("${ai.embedding.dimensions}")
    private int dimensions;

    public int ingest(String docId, int chunkSize, int overlap)
            throws Exception {

        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "分块参数不合法");
        }

        KnowledgeDocumentDO document = documentMapper.selectById(docId);
        if (document == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "文档不存在");
        }

        KnowledgeBaseDO kb =
                knowledgeBaseMapper.selectById(document.getKbId());

        if (kb == null || !Integer.valueOf(0).equals(kb.getDeleted())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "知识库不存在或已删除");
        }

        if (!embeddingId.equals(kb.getEmbeddingModel())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "当前仅配置了模型：" + embeddingId);
        }

        if (dimensions != 1536) {
            throw new IllegalStateException(
                    "当前向量表为 vector(1536)，Embedding 维度必须一致");
        }

        // 必须放在 try 外面，抢占失败不能把别人的任务改成 failed。
        if (ingestionMapper.tryStart(docId) != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "文档正在处理，或文档已禁用、删除");
        }

        log.info("开始文档入库：docId={}, collection={}",
                docId, kb.getCollectionName());

        try {
            List<ChunkPreview> chunks =
                    documentService.previewChunks(docId, chunkSize, overlap);

            if (chunks.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "文档没有可入库的内容");
            }

            // 在调用模型前检查所有块，沿用当前测试阶段的长度限制。
            for (ChunkPreview chunk : chunks) {
                if (!StringUtils.hasText(chunk.getContent())
                        || chunk.getContent().length() > 1024) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "分块 " + chunk.getChunkIndex()
                                    + " 为空或超过1024字符限制");
                }
            }

            // 事务外完成全部向量化，保留完整向量，不使用预览中的 sample。
            List<String> vectors = new ArrayList<>();
            for (ChunkPreview chunk : chunks) {
                List<Double> vector =
                        embeddingService.embed(chunk.getContent());

                vectors.add(vector.toString());
            }

            // 只有下面的数据库操作处于同一个事务中。
            TransactionTemplate transaction =
                    new TransactionTemplate(transactionManager);

            transaction.executeWithoutResult(status -> {
                ingestionMapper.deleteVectors(docId);
                ingestionMapper.deleteChunks(docId);

                for (int i = 0; i < chunks.size(); i++) {
                    ChunkPreview chunk = chunks.get(i);
                    String chunkId = IdWorker.getIdStr();

                    if (ingestionMapper.insertChunk(
                            chunkId, document, chunk) != 1) {
                        throw new IllegalStateException("分块写入失败");
                    }

                    if (ingestionMapper.insertVector(chunkId, kb.getCollectionName(), document, chunk, vectors.get(i)) != 1) {
                        throw new IllegalStateException("向量写入失败");
                    }
                }

                if (ingestionMapper.markSuccess(docId, chunks.size()) != 1) {
                    throw new IllegalStateException("文档状态更新失败");
                }
            });

            log.info("文档入库成功：docId={}, chunkCount={}",
                    docId, chunks.size());

            return chunks.size();

        } catch (Exception exception) {
            // 此时写库事务已结束/回滚，失败状态单独保存。
            try {
                ingestionMapper.markFailed(docId);
            } catch (Exception statusException) {
                exception.addSuppressed(statusException);
            }

            log.error("文档入库失败：docId={}", docId, exception);
            throw exception;
        }
    }
}