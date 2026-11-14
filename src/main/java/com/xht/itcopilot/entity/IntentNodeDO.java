package com.xht.itcopilot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_intent_node")
public class IntentNodeDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String intentCode;
    private String name;
    private Integer level;
    private String parentCode;

    private String description;

    // 原表是 TEXT，保存 JSON 数组字符串。
    private String examples;

    private Integer kind;
    private String kbId;
    private String collectionName;
    private String mcpToolId;

    private Integer sortOrder;
    private Integer enabled;
    private Integer deleted;

    private String createBy;
    private String updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}