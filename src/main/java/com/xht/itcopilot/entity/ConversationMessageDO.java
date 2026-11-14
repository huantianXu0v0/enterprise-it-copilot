package com.xht.itcopilot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_message")
public class ConversationMessageDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String conversationId;
    private String userId;

    // user：用户问题；assistant：助手回答。
    private String role;
    private String content;

    // 助手回答对应的用户消息ID。
    private String replyToMessageId;

    private String messageStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;
}