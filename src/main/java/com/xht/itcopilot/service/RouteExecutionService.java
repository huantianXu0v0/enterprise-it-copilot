package com.xht.itcopilot.service;

import com.xht.itcopilot.entity.*;
import com.xht.itcopilot.entity.RouteExecutionResult.BranchResult;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouteExecutionService {
    private final IntentRouterService router;
    private final RagChatService ragChatService;
    private final McpToolService mcpToolService;
    private final ToolArgumentService toolArgumentService; // 工具校验
    private final ChatModelService model;
    private final ConversationService conversations;
    private final ConversationMemoryService memoryService;
    private final ConversationMessageService messages;
    private final ConversationExecutionGuard guard; // 判断 该问题是否 正在执行
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public RouteExecutionResult chat(String conversationId, RagQueryRequest request) {
        validate(request); // 参数校验
        conversations.checkConversation(conversationId);
        try (var lease = guard.acquire(conversationId)) { // 一个问题只允许 一个线程在执行
            checkCancelled(); // 是否暂停
            ConversationMemory memory = memoryService.prepare(conversationId);
            // plan 内部改写和识别各执行一次，且读取的是刚 prepare 后的记忆。
            RoutePlan plan = router.plan(conversationId, request);
            List<RetrievedChunk> sources = new ArrayList<>();
            List<BranchResult> branches = new ArrayList<>();

            for (RoutePlan.Task task : plan.getTasks()) {
                checkCancelled();
                branches.add(execute(task, plan, request, memory, sources));
            }
            checkCancelled();
            sources = deduplicate(sources); // 去重
            String answer;
            if (plan.getTasks().isEmpty()) {
                answer = "暂时没有匹配到可处理的需求，请补充你要查询或办理的事项。";
            } else if (branches.stream().noneMatch(b -> "success".equals(b.getStatus()))) {
                // 全部缺参、失败或无检索结果，直接给出明确说明。
                answer = branches.stream().map(BranchResult::getMessage)
                        .distinct().collect(Collectors.joining("\n"));
            } else {
                answer = model.answerRouted(plan.getOriginalQuestion(),
                        buildContext(sources, branches), memory);
            }
            checkCancelled();
            messages.saveCompletedTurn(conversationId, plan.getOriginalQuestion(), answer);
            memoryService.afterTurn(conversationId);
            return new RouteExecutionResult(answer, plan, sources, branches);
        }
    }

    private BranchResult execute(RoutePlan.Task task, RoutePlan plan,
                                 RagQueryRequest request, ConversationMemory memory,
                                 List<RetrievedChunk> sources) {
        log.info("开始执行分支：intentCode={}, type={}", task.getIntentCode(), task.getType());
        try {
            BranchResult result = switch (task.getType()) {
                case "RAG" -> search(task, plan, request, sources);
                case "MCP" -> callTool(task, plan, memory);
                case "SYSTEM" -> new BranchResult(task, "success", "按用户需求进行系统介绍或普通交流");
                default -> throw new IllegalStateException("未知分支类型");
            };
            checkCancelled();
            log.info("分支执行完成：intentCode={}, status={}", task.getIntentCode(), result.getStatus());
            return result;
        } catch (CancellationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            checkCancelled();
            log.error("分支执行失败：intentCode={}, type={}",
                    task.getIntentCode(), task.getType(), exception);
            String message = "MCP".equals(task.getType())
                    ? "工具查询未能完成，请稍后重试。" : "知识库查询未能完成，请稍后重试。";
            return new BranchResult(task, "failed", message);
        }
    }

    private BranchResult search(RoutePlan.Task task, RoutePlan plan,
                                RagQueryRequest request, List<RetrievedChunk> sources) {
        RagQueryRequest query = new RagQueryRequest();
        query.setQuestion(plan.getRewrittenQuestion());
        query.setTopK(request.getTopK());
        query.setMinScore(request.getMinScore());
        // 只复用检索；不能调 chat()，否则会重复改写、生成答案和保存消息。
        List<RetrievedChunk> chunks = ragChatService.search(task.getKbId(), query);
        sources.addAll(chunks);
        return new BranchResult(task, chunks.isEmpty() ? "empty" : "success",
                chunks.isEmpty() ? "相关知识库未找到足够资料，请补充问题细节。"
                        : "已检索到相关文档，内容见本轮编号资料。");
    }

    private BranchResult callTool(RoutePlan.Task task, RoutePlan plan, ConversationMemory memory) {
        McpSchema.Tool tool = mcpToolService.listTools().stream()
                .filter(t -> task.getMcpToolId().equals(t.name()))
                .findFirst().orElseThrow(() -> new IllegalStateException("工具不存在"));
        // 本阶段只接入三个只读 工具；写操作需要另做确认、幂等和恢复流程。
        if (tool.annotations() == null || !Boolean.TRUE.equals(tool.annotations().readOnlyHint())) {
            return new BranchResult(task, "failed", "该工具尚未接入当前自动执行流程。");
        }
        var prepared = toolArgumentService.prepare(tool, plan, memory);
        if (!prepared.getMissing().isEmpty()) {
            String missing = prepared.getMissing().stream().map(name -> switch (name) {
                case "ticketId" -> "工单编号";
                case "timezone" -> "时区";
                default -> name;
            }).collect(Collectors.joining("、"));
            return new BranchResult(task, "needs_input", "为了完成这项查询，请补充：" + missing + "。");
        }
        checkCancelled();
        McpCallRequest call = new McpCallRequest();
        call.setToolName(tool.name());
        call.setArguments(prepared.getArguments());
        McpSchema.CallToolResult result = mcpToolService.call(call);
        checkCancelled();
        if (Boolean.TRUE.equals(result.isError())) {
            // 业务失败不能当成成功事实；不将可能包含内部信息的原始错误直接返回前端。
            log.warn("工具返回业务失败：toolName={}", tool.name());
            return new BranchResult(task, "failed", "工具未能完成查询，请检查输入或稍后重试。");
        }
        String text = result.content() == null ? "" : result.content().stream()
                .filter(content -> content instanceof McpSchema.TextContent)
                .map(content -> ((McpSchema.TextContent) content).text())
                .filter(Objects::nonNull).collect(Collectors.joining("\n"));
        if (!StringUtils.hasText(text)) {
            return new BranchResult(task, "failed", "工具未返回当前链路可处理的文本结果。");
        }
        return new BranchResult(task, "success", text);
    }

    private List<RetrievedChunk> deduplicate(List<RetrievedChunk> chunks) {
        Map<String, RetrievedChunk> unique = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            if (StringUtils.hasText(chunk.getContent())) {
                unique.putIfAbsent(chunk.getContent().strip(), chunk);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private String buildContext(List<RetrievedChunk> sources, List<BranchResult> branches) {
        StringBuilder context = new StringBuilder("本轮知识库资料：\n");
        for (int i = 0; i < sources.size(); i++) {
            RetrievedChunk chunk = sources.get(i);
            context.append("[").append(i + 1).append("] 文档：")
                    .append(chunk.getDocName()).append("\n")
                    .append(chunk.getContent()).append("\n\n");
        }
        context.append("本轮各分支执行状态和工具结果：\n")
                .append(jsonMapper.writeValueAsString(branches));
        return context.toString();
    }

    private void validate(RagQueryRequest request) {
        if (request == null || !StringUtils.hasText(request.getQuestion())
                || request.getQuestion().length() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题不能为空且不能超过1024字符");
        }
        if (request.getTopK() == null || request.getTopK() < 1 || request.getTopK() > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "topK必须在1到10之间");
        }
        Double score = request.getMinScore();
        if (score == null || !Double.isFinite(score) || score < -1 || score > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minScore必须在-1到1之间");
        }
    }
    private void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("本轮问答已取消");
        }
    }
}
