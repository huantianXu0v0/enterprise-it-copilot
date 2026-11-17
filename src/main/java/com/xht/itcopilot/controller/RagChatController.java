package com.xht.itcopilot.controller;

import com.xht.itcopilot.entity.*;
import com.xht.itcopilot.service.IntentRouterService;
import com.xht.itcopilot.service.McpToolService;
import com.xht.itcopilot.service.RagChatService;
import com.xht.itcopilot.service.RouteExecutionService;
import com.xht.itcopilot.service.RouteChatStreamService;
import io.modelcontextprotocol.spec.McpSchema;
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
    private final IntentRouterService intentRouterService;
    private final McpToolService mcpToolService;
    private final RouteExecutionService routeExecutionService; // 意图识别之后,  路由到各自链路执行

    private final RouteChatStreamService routeChatStreamService;

    @PostMapping(value = "/conversations/{conversationId}/ask/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@PathVariable("conversationId") String conversationId,
                                @RequestBody RagQueryRequest request,
                                jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return routeChatStreamService.stream(conversationId, request);
    }

    // 只检索，方便排查命中的内容是否正确。
    @PostMapping("/kb/{kbId}/search")
    public Result<List<RetrievedChunk>> search(
            @PathVariable("kbId") String kbId,
            @RequestBody RagQueryRequest request) {

        return Result.ok(ragChatService.search(kbId, request));
    }

    // 问题改写 - 检索知识库 - 生成answer
    // 同步返回
    @PostMapping("/kb/{kbId}/{conversationId}")
    public Result<RagAnswer> chat(
            @PathVariable("kbId") String kbId,
            @RequestBody RagQueryRequest request,
            @PathVariable("conversationId") String conversationId) {

        return Result.ok(ragChatService.chat(kbId, conversationId ,request));
    }

    // 流式输出 : 问题改写 - 检索知识库 - 生成answer
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

    // 路由预览 接口
    @PostMapping("/conversations/{conversationId}/route-preview")
    public Result<RoutePlan> previewRoute(@PathVariable("conversationId")String conversationId, @RequestBody RagQueryRequest request){
        return Result.ok(intentRouterService.plan(conversationId, request));
    }
    // 查看远程工具及参数定义。
    @GetMapping("/mcp/tools")
    public Result<List<McpSchema.Tool>> listMcpTools() {
        return Result.ok(mcpToolService.listTools());
    }

    // 使用明确的参数执行工具。
    @PostMapping("/mcp/call")
    public Result<McpSchema.CallToolResult> callMcpTool(
            @RequestBody McpCallRequest request) {

        return Result.ok(mcpToolService.call(request));
    }

    // 问题改写 - 意图识别 - 路由链路 - 合并生成 answer
    @PostMapping("/conversations/{conversationId}/ask")
    public Result<RouteExecutionResult> ask(
            @PathVariable("conversationId") String conversationId,
            @RequestBody RagQueryRequest request) {

        return Result.ok(
                routeExecutionService.chat(conversationId, request));
    }

}