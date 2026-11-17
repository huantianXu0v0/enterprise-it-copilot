package com.xht.itcopilot.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

@Data
@AllArgsConstructor
public class RouteExecutionResult {
    private String answer;
    private RoutePlan plan;
    private List<RetrievedChunk> sources;
    private List<BranchResult> branches;

    @Data
    @AllArgsConstructor
    public static class BranchResult {
        private RoutePlan.Task task;
        // success / empty / needs_input / failed
        private String status;
        // 成功的工具正文，或缺参、失败说明。
        private String message;
    }
}
