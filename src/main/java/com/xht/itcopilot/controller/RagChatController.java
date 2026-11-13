package com.xht.itcopilot.controller;

import com.xht.itcopilot.entity.*;
import com.xht.itcopilot.service.RagChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    @PostMapping("/kb/{kbId}/{coversationId}")
    public Result<RagAnswer> chat(
            @PathVariable("kbId") String kbId,
            @RequestBody RagQueryRequest request) {

        return Result.ok(ragChatService.chat(kbId, request));
    }
}