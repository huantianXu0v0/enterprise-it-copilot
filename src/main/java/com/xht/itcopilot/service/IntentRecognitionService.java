package com.xht.itcopilot.service;

import com.xht.itcopilot.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.CancellationException;

@Service
@RequiredArgsConstructor
public class IntentRecognitionService {

    private final ConversationMemoryService memoryService;
    private final ChatModelService chatModelService;
    private final IntentTreeCacheService cacheService;
    private final McpToolService tools;

    public IntentRecognitionResult recognize(String conversationId, RagQueryRequest request) {
        return recognize(conversationId,request,false);
    }

    public IntentRecognitionResult recognize(String conversationId, RagQueryRequest request, boolean fresh) {

        if (request == null
                || !StringUtils.hasText(request.getQuestion())
                || request.getQuestion().length() > 1024) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "问题不能为空且不能超过1024字符");
        }

        String original = request.getQuestion().strip();

        // 预览只读记忆，不保存消息，也不触发摘要更新。
        ConversationMemory memory = memoryService.load(conversationId);

        String rewritten = chatModelService.rewriteQuestion(original, memory);

        List<IntentTreeNode> tree = fresh ? cacheService.refreshTree() : cacheService.getTree();

        // 工具目录可读时，剔除旧项目里绑定了不存在工具的节点。
        // 服务临时离线时保留节点，让执行层明确报告失败，而非误判为不支持。
        Set<String> availableTools = null;
        try {
            availableTools = new HashSet<>();
            for (var tool : tools.listTools()) availableTools.add(tool.name());
        } catch (RuntimeException exception) {
            if (Thread.currentThread().isInterrupted() || exception instanceof CancellationException)
                throw new CancellationException("意图识别已取消");
            availableTools = null;
        }
        if (availableTools != null) tree = filterTools(tree, availableTools);

        // 建立可执行节点索引，用于校验模型返回的编码。
        Map<String, IntentTreeNode> executableNodes = new HashMap<>();
        collectExecutable(tree, executableNodes);

        if (executableNodes.isEmpty()) {
            return new IntentRecognitionResult(
                    original, rewritten, "no_match", List.of());
        }

        List<String> codes = chatModelService.recognizeIntents(original, rewritten, tree);

        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("意图识别任务已取消");
        }

        List<IntentTreeNode> matches = new ArrayList<>();

        for (String code : codes) {
            IntentTreeNode node = executableNodes.get(code);

            if (node == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "模型返回了不存在或不可执行的意图编码");
            }

            // 资源绑定来自后端配置，而不是模型输出。
            matches.add(node);
        }

        return new IntentRecognitionResult(
                original,
                rewritten,
                matches.isEmpty() ? "no_match" : "matched",
                matches);
    }

    private List<IntentTreeNode> filterTools(List<IntentTreeNode> nodes, Set<String> available) {
        List<IntentTreeNode> result = new ArrayList<>();
        for (IntentTreeNode node : nodes) {
            if (node.getChildren().isEmpty() && Integer.valueOf(2).equals(node.getKind())
                    && !available.contains(node.getMcpToolId())) continue;
            IntentTreeNode copy = new IntentTreeNode();
            org.springframework.beans.BeanUtils.copyProperties(node, copy);
            copy.setChildren(filterTools(node.getChildren(), available));
            if (!node.getChildren().isEmpty() && copy.getChildren().isEmpty()) continue;
            result.add(copy);
        }
        return result;
    }

    private void collectExecutable(
            List<IntentTreeNode> nodes,
            Map<String, IntentTreeNode> result) {

        for (IntentTreeNode node : nodes) {
            if (node.isExecutable() && node.getChildren().isEmpty()) {
                result.put(node.getIntentCode(), node);
            }

            collectExecutable(node.getChildren(), result);
        }
    }
}