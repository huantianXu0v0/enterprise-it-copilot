package com.xht.itcopilot.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class RagAnswer {

    private String answer;

    // 本次提供给模型的参考片段，顺序对应答案中的 [1]、[2]。
    private List<RetrievedChunk> sources;
}