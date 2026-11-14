package com.xht.itcopilot.controller;

import com.xht.itcopilot.entity.ConversationMessageDO;
import com.xht.itcopilot.entity.Result;
import com.xht.itcopilot.entity.ConversationMemory;
import com.xht.itcopilot.entity.ConversationSummaryDO;
import com.xht.itcopilot.service.ConversationMemoryService;
import com.xht.itcopilot.service.ConversationExecutionGuard;
import com.xht.itcopilot.service.ConversationMessageService;
import com.xht.itcopilot.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;
    private final ConversationMemoryService memoryService;
    private final ConversationExecutionGuard executionGuard;
    /**
     * 新增会话
     */
    @PostMapping
    public Result<Map<String,String>> createConversation(){
        String conversationId = conversationService.createConversation();

        return Result.ok(Map.of("conversationId", conversationId));
    }

    /**
     * 查看
     * 历史消息
     */
    @GetMapping("/{conversationId}/messages/recent")
    public Result<List<ConversationMessageDO>> recentMessages(
            @PathVariable("conversationId") String conversationId) {

        return Result.ok(
                conversationMessageService.loadRecentHistory(conversationId));
    }
    // 查看实际模型记忆：摘要 + 摘要之后全部未压缩问答。
    @GetMapping("/{conversationId}/memory")
    public Result<ConversationMemory> memory(@PathVariable("conversationId") String conversationId) {
        return Result.ok(memoryService.load(conversationId));
    }

    @GetMapping("/{conversationId}/summary")
    public Result<ConversationSummaryDO> summary(@PathVariable("conversationId") String conversationId) {
        return Result.ok(memoryService.loadLatestSummary(conversationId));
    }

    // 用于检查和失败重试，未达到阈值时不会调用模型。
    @PostMapping("/{conversationId}/memory/compact")
    public Result<ConversationMemory> compact(@PathVariable("conversationId") String conversationId) {
        conversationService.checkConversation(conversationId);
        try (var lease = executionGuard.acquire(conversationId)) {
            return Result.ok(memoryService.compact(conversationId));
        }
    }
}
