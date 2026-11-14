package com.xht.itcopilot.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class IntentRecognitionResult {

    private String originalQuestion;
    private String rewrittenQuestion;

    // matched 或 no_match；调用失败通过 Result.fail 返回。
    private String status;

    private List<IntentTreeNode> intents;
}