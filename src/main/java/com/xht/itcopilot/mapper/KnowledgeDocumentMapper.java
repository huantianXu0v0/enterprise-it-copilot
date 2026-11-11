package com.xht.itcopilot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xht.itcopilot.entity.KnowledgeDocumentDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentDO> {

    @Select("""
    SELECT collection_name
    FROM t_knowledge_base
    WHERE id = #{kbId} AND deleted = 0
    """)
    String findCollectionName(@Param("kbId") String kbId);
}
