package com.xht.itcopilot.entity;

import lombok.Data;

@Data
public class RagQueryRequest {

    private String question;

    // 仅新自动路由 SSE 接口使用；控制最终回答的思考模式。
    private boolean deepThinking = false;

    // 最终交给大模型的最大分块数量。
    private Integer topK = 5;

    // 实验起点，需要根据你的测试问题调整。
    private Double minScore = 0.3;
}