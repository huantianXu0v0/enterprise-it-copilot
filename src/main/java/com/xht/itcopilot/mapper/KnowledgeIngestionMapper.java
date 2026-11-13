package com.xht.itcopilot.mapper;

import com.xht.itcopilot.entity.ChunkPreview;
import com.xht.itcopilot.entity.KnowledgeDocumentDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface KnowledgeIngestionMapper {

    // 条件更新：同一文档只有一个请求能够成功进入 running。
    @Update("""
        UPDATE t_knowledge_document
        SET status = 'running',
            updated_by = 'local',
            update_time = CURRENT_TIMESTAMP
        WHERE id = #{docId}
          AND deleted = 0
          AND enabled = 1
          AND status IN ('pending', 'failed', 'success')
        """)
    int tryStart(@Param("docId") String docId);

    @Delete("""
        DELETE FROM t_knowledge_vector
        WHERE metadata ->> 'doc_id' = #{docId}
        """)
    int deleteVectors(@Param("docId") String docId);

    @Delete("""
        DELETE FROM t_knowledge_chunk
        WHERE doc_id = #{docId}
        """)
    int deleteChunks(@Param("docId") String docId);

    @Insert("""
        INSERT INTO t_knowledge_chunk (
            id, kb_id, doc_id, chunk_index,
            content, embedding_text, char_count,
            enabled, created_by, deleted
        )
        VALUES (
            #{id}, #{doc.kbId}, #{doc.id}, #{chunk.chunkIndex},
            #{chunk.content}, #{chunk.content},
            char_length(#{chunk.content}),
            1, 'local', 0
        )
        """)
    int insertChunk(
            @Param("id") String id,
            @Param("doc") KnowledgeDocumentDO document,
            @Param("chunk") ChunkPreview chunk);

    @Insert("""
        INSERT INTO t_knowledge_vector (
            id, collection_name, content, metadata, embedding
        )
        VALUES (
            #{id},
            #{collectionName},
            #{chunk.content},
            jsonb_build_object(
                'doc_id', CAST(#{doc.id} AS text),
                'kb_id', CAST(#{doc.kbId} AS text),
                'chunk_index', CAST(#{chunk.chunkIndex} AS integer),
                'type', CAST(#{chunk.type} AS text)
            ),
            CAST(#{vector} AS vector)
        )
        """)
    int insertVector(
            @Param("id") String id,
            @Param("collectionName") String collectionName,
            @Param("doc") KnowledgeDocumentDO document,
            @Param("chunk") ChunkPreview chunk,
            @Param("vector") String vector);

    @Update("""
        UPDATE t_knowledge_document
        SET status = 'success',
            chunk_count = #{count},
            updated_by = 'local',
            update_time = CURRENT_TIMESTAMP
        WHERE id = #{docId}
          AND status = 'running'
          AND deleted = 0
          AND enabled = 1
        """)
    int markSuccess(
            @Param("docId") String docId,
            @Param("count") int count);

    @Update("""
        UPDATE t_knowledge_document
        SET status = 'failed',
            updated_by = 'local',
            update_time = CURRENT_TIMESTAMP
        WHERE id = #{docId}
          AND status = 'running'
        """)
    int markFailed(@Param("docId") String docId);
}