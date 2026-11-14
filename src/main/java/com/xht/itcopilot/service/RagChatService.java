package com.xht.itcopilot.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xht.itcopilot.entity.*;
import com.xht.itcopilot.mapper.KnowledgeBaseMapper;
import com.xht.itcopilot.mapper.KnowledgeRetrievalMapper;
import jakarta.annotation.Resource;
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



    // 构造方法
    public RagChatService(
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeRetrievalMapper retrievalMapper,
            EmbeddingService embeddingService,
            ChatModelService chatModelService,
            @Qualifier("ragChatExecutor") AsyncTaskExecutor ragChatExecutor) {

        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.retrievalMapper = retrievalMapper;
        this.embeddingService = embeddingService;
        this.chatModelService = chatModelService;
        this.ragChatExecutor = ragChatExecutor;
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
        // 检索之前 先 返回上下文


        List<RetrievedChunk> chunks = search(kbId, request);

        // 没有合格的片段时，不调用生成模型。
        if (chunks.isEmpty()) {
            return new RagAnswer(
                    "当前知识库没有检索到足够相关的内容，请补充问题细节。",
                    List.of());
        }

        // 4. 按顺序给片段编号，便于回答引用。
        StringBuilder context = new StringBuilder();

        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);

            context.append("[").append(i + 1).append("]\n")
                    .append("文档：").append(chunk.getDocName()).append("\n")
                    .append("内容：\n").append(chunk.getContent())
                    .append("\n\n");
        }

        // 5. 使用检索上下文生成答案。
        String answer = chatModelService.answer(request.getQuestion().strip(), context.toString());

        return new RagAnswer(answer, chunks);
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

    public SseEmitter streamChat(String kbId, RagQueryRequest request) {

        // 参数错误在建立流之前抛出，仍由异常处理器返回 Result.fail。
        validate(request);

        // 本次异步请求的超时时间：120秒。
        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicBoolean closed = new AtomicBoolean(false);

        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                List<RetrievedChunk> chunks = search(kbId, request);

                // 先把实际使用的参考资料发送给前端。
                sendEvent(emitter, closed, "sources", chunks);

                if (chunks.isEmpty()) {
                    sendEvent(emitter, closed, "delta", Map.of(
                            "text", "当前知识库没有检索到足够相关的内容，请补充问题细节。"
                    ));
                } else {
                    // 将chunks变成 [1] xxx ; [2] xxx 的 字符串的形式
                    String context = buildContext(chunks);

                    // 下一步实现这个方法：
                    // 模型每返回一段文字，就执行一次这里的回调。
                    chatModelService.answerStream(
                            request.getQuestion().strip(),
                            context,
                            text -> sendEvent(emitter, closed, "delta", Map.of("text", text))
                    );
                }

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
}