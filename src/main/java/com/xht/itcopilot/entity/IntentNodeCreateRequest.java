package com.xht.itcopilot.entity;

import lombok.Data;

import java.util.List;

@Data
public class IntentNodeCreateRequest {

    private String intentCode;
    private String name;
    private String parentCode;

    private String description;
    private List<String> examples;

    // 仅第2层配置：0=RAG，1=系统交互，2=MCP。
    private Integer kind;
    private String kbId;
    private String mcpToolId;
}