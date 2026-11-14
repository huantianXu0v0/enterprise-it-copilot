package com.xht.itcopilot.service;

import com.xht.itcopilot.entity.*;
import com.xht.itcopilot.mapper.KnowledgeBaseMapper;
import com.xht.itcopilot.mapper.KnowledgeRetrievalMapper;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class RagChatService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeRetrievalMapper retrievalMapper;
    private final EmbeddingService embeddingService;
    private final ChatModelService chatModelService;
    private final AsyncTaskExecutor ragChatExecutor;
    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;

    private final ConversationMemoryService memoryService;
    private final ConversationExecutionGuard executionGuard;

    // 构造方法
    public RagChatService(
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeRetrievalMapper retrievalMapper,
            EmbeddingService embeddingService,
            ChatModelService chatModelService,
            @Qualifier("ragChatExecutor") AsyncTaskExecutor ragChatExecutor,
            ConversationService conversationService,
            ConversationMessageService conversationMessageService,
            ConversationMemoryService memoryService,
            ConversationExecutionGuard executionGuard) {

        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.retrievalMapper = retrievalMapper;
        this.embeddingService = embeddingService;
        this.chatModelService = chatModelService;
        this.ragChatExecutor = ragChatExecutor;
        this.conversationService = conversationService;
        this.conversationMessageService = conversationMessageService;
        this.memoryService = memoryService;
        this.executionGuard = executionGuard;
    }

    @Value("${ai.embedding.id:qwen-emb-8b}")
    private String embeddingId;

    public List<RetrievedChunk> search(
            String kbId, RagQueryRequest request) {

        validate(request);

        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);

        if (kb == null || !Integer.valueOf(0).equals(kb.getDeleted())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "知识库不存在或已删除");
        }

        // 检索问题与文档必须使用同一个 Embedding 模型。
        if (!embeddingId.equals(kb.getEmbeddingModel())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "当前未配置该知识库的 Embedding 模型");
        }

        // 1. 问题向量化。
        List<Double> vector =
                embeddingService.embed(request.getQuestion().strip());

        if (vector.size() != 1536
                || vector.stream().allMatch(value -> value == 0.0)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "查询向量维度异常或为零向量");
        }

        // 2. 多取一些候选，给后续过滤、去重留出余量。
        List<RetrievedChunk> candidates = retrievalMapper.search(
                kbId,
                kb.getCollectionName(),
                vector.toString(),
                request.getTopK() * 3);

        // 3. 过滤低分、空内容和完全重复的正文。
        List<RetrievedChunk> results = new ArrayList<>();
        Set<String> seenContents = new HashSet<>();

        for (RetrievedChunk chunk : candidates) {
            Double score = chunk.getScore();

            if (score == null || !Double.isFinite(score)
                    || score < request.getMinScore()) {
                continue;
            }

            if (!StringUtils.hasText(chunk.getContent())) {
                continue;
            }

            if (!seenContents.add(chunk.getContent().strip())) {
                continue;
            }

            results.add(chunk);

            if (results.size() >= request.getTopK()) {
                break;
            }
        }

        return results;
    }

    public RagAnswer chat(String kbId, String conversationId, RagQueryRequest request) {
        validate(request);
        conversationService.checkConversation(conversationId);
        try (var lease = executionGuard.acquire(conversationId)) {
            ConversationMemory memory = memoryService.prepare(conversationId);

            RagQueryRequest retrievalRequest =
                    buildRetrievalRequest(request, memory);

            List<RetrievedChunk> chunks = search(kbId, retrievalRequest);
            String answer;
            if (chunks.isEmpty() && memory.getMessages().isEmpty()
                    && !StringUtils.hasText(memory.getSummary())) {
                answer = "当前知识库没有检索到足够相关的内容，请补充问题细节。";
            } else {
                answer = chatModelService.answer(request.getQuestion().strip(), buildContext(chunks), memory);
            }
            conversationMessageService.saveCompletedTurn(conversationId, request.getQuestion().strip(), answer);
            memoryService.afterTurn(conversationId);
            return new RagAnswer(answer, chunks);
        }
    }

    private void validate(RagQueryRequest request) {

        if (request == null
                || !StringUtils.hasText(request.getQuestion())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "问题不能为空");
        }

        // 沿用你现有 EmbeddingService 的应用限制。
        if (request.getQuestion().length() > 1024) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "问题不能超过1024字符");
        }

        if (request.getTopK() == null
                || request.getTopK() < 1
                || request.getTopK() > 10) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "topK 必须在1到10之间");
        }

        Double score = request.getMinScore();

        if (score == null || !Double.isFinite(score)
                || score < -1 || score > 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "minScore 必须在-1到1之间");
        }
    }

    public SseEmitter streamChat(String kbId, String conversationId, RagQueryRequest request) {

        // 参数错误在建立流之前抛出，仍由异常处理器返回 Result.fail。
        validate(request);

        // 校验会话 存在 且 属于当前用户
        conversationService.checkConversation(conversationId);

        // 本次异步请求的超时时间：120秒。
        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicBoolean closed = new AtomicBoolean(false);

        FutureTask<Void> task = new FutureTask<>(() -> {
            try (var lease = executionGuard.acquire(conversationId)) {
                if (closed.get() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("任务已取消");
                }
                ConversationMemory memory = memoryService.prepare(conversationId);

                RagQueryRequest retrievalRequest =
                        buildRetrievalRequest(request, memory);

                List<RetrievedChunk> chunks = search(kbId, retrievalRequest);

                sendEvent(emitter, closed, "sources", chunks);


                // 每个请求自己创建一个，只由当前后台任务追加。
                StringBuilder fullAnswer = new StringBuilder();

                if (chunks.isEmpty() && memory.getMessages().isEmpty()
                        && !StringUtils.hasText(memory.getSummary())) {
                    String text = "当前知识库没有检索到足够相关的内容，请补充问题细节。";

                    fullAnswer.append(text);
                    sendEvent(emitter, closed, "delta", Map.of("text", text));
                } else {
                    // 将chunks变成 [1] xxx ; [2] xxx 的 字符串的形式
                    String context = buildContext(chunks);

                    // 模型每返回一段文字，就执行一次回调。
                    chatModelService.answerStream(
                            request.getQuestion().strip(),
                            context,
                            memory,
                            text ->
                            {
                                fullAnswer.append(text);
                                sendEvent(emitter, closed, "delta", Map.of("text", text));
                            }
                    );
                }

                // 已知连接结束或任务已取消，不再开始保存。
                if (closed.get() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("任务已取消");
                }

                // 只有模型正常完成，或者正常返回“未找到资料”，才走到这里。
                conversationMessageService.saveCompletedTurn(
                        conversationId,
                        request.getQuestion().strip(),
                        fullAnswer.toString());

                // 每累计10轮未压缩历史，合并最早5轮；失败保留旧摘要及原文。
                memoryService.afterTurn(conversationId);

                // 保存成功后，再通知前端本轮完成。
                sendEvent(emitter, closed, "done", Map.of("finished", true));
            } catch (UncheckedIOException | CancellationException exception) {
                // 连接写入失败或任务取消，停止发送。
                closed.set(true);

            } catch (Exception exception) {
                log.error("流式问答失败：kbId={}", kbId, exception);

                String message = "问答处理失败，请查看服务端日志";
                if (exception instanceof ResponseStatusException statusException
                        && statusException.getReason() != null) {
                    message = statusException.getReason();
                }

                try {
                    sendEvent(emitter, closed, "error",
                            Map.of("message", message));
                } catch (RuntimeException sendException) {
                    // 连接已经不可用，不再尝试发送。
                    closed.set(true);
                }

            } finally {
                if (closed.compareAndSet(false, true)) {
                    emitter.complete();
                }
            }

            return null;
        });

        // 连接超时：停止任务，并结束响应。
        emitter.onTimeout(() -> {
            closed.set(true);
            task.cancel(true);
            emitter.complete();
        });

        emitter.onError(error -> {
            closed.set(true);
            task.cancel(true);
        });

        emitter.onCompletion(() -> {
            closed.set(true);
            task.cancel(true);
        });

        try {
            ragChatExecutor.execute(task);
        } catch (RejectedExecutionException exception) {
            task.cancel(true);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "服务繁忙，请稍后重试");
        }

        // 提交任务后立即返回，不等待检索和模型生成完成。
        return emitter;
    }

    private String buildContext(List<RetrievedChunk> chunks) {

        StringBuilder context = new StringBuilder();

        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);

            context.append("[").append(i + 1).append("]\n")
                    .append("文档：").append(chunk.getDocName()).append("\n")
                    .append("内容：\n").append(chunk.getContent())
                    .append("\n\n");
        }

        return context.toString();
    }

    private void sendEvent(SseEmitter emitter, AtomicBoolean closed, String eventName, Object data) {

        if (closed.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("SSE任务已经结束");
        }

        try {
            emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException exception) {
            // 转成非检查异常，让发送失败能从回调中向外传递。
            throw new UncheckedIOException(exception);
        }
    }

    // 问题改写
    public Map<String, Object> previewRewrite(
            String conversationId,
            RagQueryRequest request) {

        validate(request);

        // load 内部会校验会话归属；预览不触发摘要更新。
        ConversationMemory memory = memoryService.load(conversationId);

        String original = request.getQuestion().strip();
        String rewritten = chatModelService.rewriteQuestion(original, memory);

        return Map.of(
                "originalQuestion", original,
                "rewrittenQuestion", rewritten,
                "changed", !original.equals(rewritten)
        );
    }

    private RagQueryRequest buildRetrievalRequest(
            RagQueryRequest original,
            ConversationMemory memory) {

        String rewritten = chatModelService.rewriteQuestion(
                original.getQuestion(), memory);

        RagQueryRequest retrievalRequest = new RagQueryRequest();
        retrievalRequest.setQuestion(rewritten);
        retrievalRequest.setTopK(original.getTopK());
        retrievalRequest.setMinScore(original.getMinScore());

        return retrievalRequest;
    }
}