package com.xht.itcopilot.service;

import com.xht.itcopilot.entity.*;
import com.xht.itcopilot.mapper.KnowledgeBaseMapper;
import com.xht.itcopilot.mapper.KnowledgeRetrievalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeRetrievalMapper retrievalMapper;
    private final EmbeddingService embeddingService;
    private final ChatModelService chatModelService;

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

    public RagAnswer chat(String kbId, RagQueryRequest request) {

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
}