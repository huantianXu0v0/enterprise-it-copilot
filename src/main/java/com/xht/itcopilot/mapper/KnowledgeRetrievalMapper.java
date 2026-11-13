package com.xht.itcopilot.mapper;

import com.xht.itcopilot.entity.RetrievedChunk;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface KnowledgeRetrievalMapper {

    @Select("""
        SELECT
            c.id AS "chunkId",
            c.doc_id AS "docId",
            d.doc_name AS "docName",
            c.chunk_index AS "chunkIndex",
            c.content,
            1 - (v.embedding <=> CAST(#{vector} AS vector)) AS score
        FROM t_knowledge_vector v
        JOIN t_knowledge_chunk c ON c.id = v.id
        JOIN t_knowledge_document d ON d.id = c.doc_id
        WHERE v.collection_name = #{collectionName}
          AND c.kb_id = #{kbId}
          AND d.kb_id = #{kbId}
          AND c.deleted = 0
          AND c.enabled = 1
          AND d.deleted = 0
          AND d.enabled = 1
        ORDER BY v.embedding <=> CAST(#{vector} AS vector)
        LIMIT #{limit}
        """)
    List<RetrievedChunk> search(
            @Param("kbId") String kbId,
            @Param("collectionName") String collectionName,
            @Param("vector") String vector,
            @Param("limit") int limit);
}