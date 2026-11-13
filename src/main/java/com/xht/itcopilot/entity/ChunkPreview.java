package com.xht.itcopilot.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkPreview {

    private Integer chunkIndex;

    // TEXT / TABLE / CODE
    private String type;

    private String content;
}