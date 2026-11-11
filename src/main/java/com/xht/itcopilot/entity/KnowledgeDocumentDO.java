package com.xht.itcopilot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_knowledge_document")
public class KnowledgeDocumentDO {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String kbId;
    private String docName;

    // RustFS 中的对象 key
    private String fileUrl;

    // 文件类型，例如 pdf、docx
    private String fileType;

    // MIME 类型，例如 application/pdf
    private String mimeType;

    private Long fileSize;

    // pending / running / success / failed
    private String status;

    private Integer chunkCount;
    private Integer enabled;

    private String createdBy;
}
