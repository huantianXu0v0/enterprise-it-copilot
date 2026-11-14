package com.xht.itcopilot.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IntentTreeNode {

    private String intentCode;
    private String name;
    private Integer level;
    private String description;

    private List<String> examples = new ArrayList<>();

    // 执行动作只在第2层节点上返回。
    private Integer kind;
    private String kbId;
    private String collectionName;
    private String mcpToolId;

    // 不存数据库，查询时组装。
    private List<IntentTreeNode> children = new ArrayList<>();
}