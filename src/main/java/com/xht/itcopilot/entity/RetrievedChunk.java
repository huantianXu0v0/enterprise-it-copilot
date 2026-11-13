package com.xht.itcopilot.entity;

import lombok.Data;

@Data
public class RetrievedChunk {

    private String chunkId;
    private String docId;
    private String docName;
    private Integer chunkIndex;
    private String content;
    private Double score;
}