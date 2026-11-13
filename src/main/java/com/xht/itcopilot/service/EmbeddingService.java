package com.xht.itcopilot.service;

import com.xht.itcopilot.entity.EmbeddingResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class EmbeddingService {

    @Value("${ai.embedding.url}")
    private String url;

    @Value("${ai.embedding.api-key}")
    private String apiKey;

    @Value("${ai.embedding.model}")
    private String model;

    @Value("${ai.embedding.dimensions}")
    private int dimensions;

    private final RestClient restClient;
    // 构造方法
    public EmbeddingService() {

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(60));

        restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    // 文本向量化,调用 embed模型 ,返回 1536维数组
    public List<Double> embed(String text) {
        if (!StringUtils.hasText(text)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "向量化文本不能为空");
        }

        // 当前测试阶段的应用限制，不是模型的 token 上限。
        if (text.length() > 1024) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "当前测试阶段最多输入1024字符");
        }

        Map<String, Object> request = Map.of(
                "model", model,
                "input", List.of(text),
                "dimensions", dimensions,
                "encoding_format", "float"
        );

//        log.info("开始向量化：model={}, chars={}", model, text.length());

        EmbeddingResponse response;
        try {
            response = restClient.post()
                    .uri(url)
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(EmbeddingResponse.class);
        } catch (RestClientException exception) {
            log.error("Embedding 调用失败，model={}", model, exception);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Embedding 调用失败，请查看控制台日志",
                    exception);
        }

        if (response == null || response.getData() == null
                || response.getData().size() != 1
                || response.getData().get(0) == null) {
            throw new IllegalStateException("Embedding 返回结果数量异常");
        }

        List<Double> vector = response.getData().get(0).getEmbedding();

        if (vector == null || vector.size() != dimensions
                || vector.stream().anyMatch(
                value -> value == null || !Double.isFinite(value))) {
            throw new IllegalStateException("Embedding 返回的向量维度或数值异常");
        }

//        log.info("向量化完成：model={}, dimensions={}", model, vector.size());
        return vector;
    }
}