package com.xht.itcopilot.service;

import com.xht.itcopilot.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRouterService {

    private final IntentRecognitionService recognitionService;
    private final IntentNodeService intentNodeService;

    public RoutePlan plan(
            String conversationId,
            RagQueryRequest request) {

        // 内部已经完成：会话校验、读取历史、改写、识别意图。
        IntentRecognitionResult recognition =
                recognitionService.recognize(conversationId, request);

        if (recognition.getIntents().isEmpty()) {
            return new RoutePlan(
                    recognition.getOriginalQuestion(),
                    recognition.getRewrittenQuestion(),
                    "no_match",
                    List.of());
        }

        // 识别使用缓存；准备执行时，核对数据库中的最新配置。
        Map<String, IntentTreeNode> currentNodes = new HashMap<>();
        collectExecutable(intentNodeService.buildTree(), currentNodes);

        // 保留任务顺序，同时对相同资源去重。
        Map<String, RoutePlan.Task> tasks = new LinkedHashMap<>();

        for (IntentTreeNode matched : recognition.getIntents()) {
            IntentTreeNode current =
                    currentNodes.get(matched.getIntentCode());

            checkCurrentNode(matched, current);

            RoutePlan.Task task;
            String resourceKey;

            switch (current.getKind()) {
                case 0 -> {
                    task = new RoutePlan.Task(
                            current.getIntentCode(),
                            "RAG",
                            current.getKbId(),
                            null);

                    resourceKey = "RAG:" + current.getKbId();
                }

                case 1 -> {
                    task = new RoutePlan.Task(
                            current.getIntentCode(),
                            "SYSTEM",
                            null,
                            null);

                    resourceKey = "SYSTEM:" + current.getIntentCode();
                }

                case 2 -> {
                    task = new RoutePlan.Task(
                            current.getIntentCode(),
                            "MCP",
                            null,
                            current.getMcpToolId());

                    resourceKey = "MCP:" + current.getMcpToolId();
                }

                default -> throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "意图配置包含不支持的类型");
            }

            tasks.putIfAbsent(resourceKey, task);
        }

        List<RoutePlan.Task> result = new ArrayList<>(tasks.values());

        log.info("路由计划生成：conversationId={}, tasks={}",
                conversationId, result);

        return new RoutePlan(
                recognition.getOriginalQuestion(),
                recognition.getRewrittenQuestion(),
                "ready",
                result);
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

    private void checkCurrentNode(
            IntentTreeNode matched,
            IntentTreeNode current) {

        if (current == null || current.getKind() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "意图已失效，请刷新意图缓存后重试");
        }

        // 不能悄悄使用识别之后发生变化的资源绑定。
        boolean changed =
                !Objects.equals(matched.getKind(), current.getKind())
                        || !Objects.equals(matched.getKbId(), current.getKbId())
                        || !Objects.equals(
                        matched.getMcpToolId(), current.getMcpToolId());

        if (changed) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "意图资源绑定已变化，请刷新意图缓存后重试");
        }
    }
}