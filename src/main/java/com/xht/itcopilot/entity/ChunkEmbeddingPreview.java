package com.xht.itcopilot.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkEmbeddingPreview {

    private Integer chunkIndex;
    private String type;
    private Integer dimensions;
    private List<Double> sample;
}