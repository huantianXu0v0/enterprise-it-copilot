package com.xht.itcopilot.entity;

import lombok.Data;

@Data
public class RagQueryRequest {

    private String question;

    // 最终交给大模型的最大分块数量。
    private Integer topK = 5;

    // 实验起点，需要根据你的测试问题调整。
    private Double minScore = 0.3;
}