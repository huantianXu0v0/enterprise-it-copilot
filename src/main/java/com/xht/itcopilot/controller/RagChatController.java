package com.xht.itcopilot.controller;

import com.xht.itcopilot.entity.*;
import com.xht.itcopilot.service.RagChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/chat")
public class RagChatController {

    private final RagChatService ragChatService;

    // 只检索，方便排查命中的内容是否正确。
    @PostMapping("/kb/{kbId}/search")
    public Result<List<RetrievedChunk>> search(
            @PathVariable("kbId") String kbId,
            @RequestBody RagQueryRequest request) {

        return Result.ok(ragChatService.search(kbId, request));
    }

    // 检索 + 生成答案。
    // 同步返回
    @PostMapping("/kb/{kbId}/{conversationId}")
    public Result<RagAnswer> chat(
            @PathVariable("kbId") String kbId,
            @RequestBody RagQueryRequest request,
            @PathVariable("conversationId") String conversationId) {

        return Result.ok(ragChatService.chat(kbId, conversationId ,request));
    }

    // 流式输出
    @PostMapping(
            value = "/kb/{kbId}/stream/{conversationId}",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamChat(
            @PathVariable("kbId") String kbId,
            @RequestBody RagQueryRequest request,
            @PathVariable("conversationId") String conversationId) {

        return ragChatService.streamChat(kbId, conversationId, request);
    }

    // 改写预览 接口
    @PostMapping("/conversations/{conversationId}/rewrite-preview")
    public Result<Map<String, Object>> previewRewrite(
            @PathVariable("conversationId") String conversationId,
            @RequestBody RagQueryRequest request) {

        return Result.ok(
                ragChatService.previewRewrite(conversationId, request));
    }

}