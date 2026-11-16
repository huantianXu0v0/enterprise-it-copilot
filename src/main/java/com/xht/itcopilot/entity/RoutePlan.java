package com.xht.itcopilot.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class RoutePlan {

    private String originalQuestion;
    private String rewrittenQuestion;

    // ready：已生成任务；no_match：没有匹配意图。
    private String status;

    private List<Task> tasks;

    @Data
    @AllArgsConstructor
    public static class Task {

        // 方便排查任务是哪个意图触发的。
        private String intentCode;

        // RAG、SYSTEM、MCP。
        private String type;

        private String kbId;
        private String mcpToolId;
    }
}