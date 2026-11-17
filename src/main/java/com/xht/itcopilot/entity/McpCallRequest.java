package com.xht.itcopilot.entity;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class McpCallRequest {

    private String toolName;

    private Map<String, Object> arguments = new HashMap<>();
}