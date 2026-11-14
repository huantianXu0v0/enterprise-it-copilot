package com.xht.itcopilot.controller;

import com.xht.itcopilot.entity.Result;
import com.xht.itcopilot.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    /**
     * 新增会话
     */
    @PostMapping
    public Result<Map<String,String>> createConversation(){
        String conversationId = conversationService.createConversation();

        return Result.ok(Map.of("conversationId", conversationId));
    }
}
