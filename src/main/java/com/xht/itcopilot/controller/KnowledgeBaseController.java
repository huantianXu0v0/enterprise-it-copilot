package com.xht.itcopilot.controller;

import com.xht.itcopilot.entity.ChunkEmbeddingPreview;
import com.xht.itcopilot.entity.ChunkPreview;
import com.xht.itcopilot.entity.Result;
import com.xht.itcopilot.service.*;
import lombok.RequiredArgsConstructor;
import org.apache.tika.exception.TikaException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeDocumentService knowledgeDocumentService;

    private final KnowledgeBaseServiceImpl knowledgeBaseService;

    private final EmbeddingService embeddingService;

    private final KnowledgeIngestionService knowledgeIngestionService;
    /**
     * 创建知识库
     */
    @PostMapping("/kb")
    public Result createKb(@RequestParam("kb_name")String kb_name, @RequestParam("collection_name")String collection_name, @RequestParam("embedding_model")String embedding_model){
        String kbId = knowledgeBaseService.create(kb_name, collection_name, embedding_model);

        return Result.ok(Map.of("kbId", kbId));
    }

    /**
     * 上传文件和导入 URL，保存文件并登记文档，返回 docId。
     */
    @PostMapping(value = "/kb/{kbId}/docs/upload",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result upload(@PathVariable("kbId") String kbId, @RequestPart("file") MultipartFile file) throws IOException {
        // 文件上传后 返回 对象key

        String docId = knowledgeDocumentService.upload(kbId, file);
        return Result.ok(Map.of("docId", docId));
    }

    /**
     * 文档 解析
     * [ docId -> file_url -> 从RustFS读取 -> Tika解析]
     */
    @PostMapping("/kb/{docId}/docs/parse")
    public Result parse(@PathVariable("docId") String docId) throws TikaException, IOException, SAXException {
        String html = knowledgeDocumentService.parse(docId);

        return  Result.ok(Map.of("docId", docId, "xhtml",html));
    }

    /**
     *  分块预览
     */
    @PostMapping("/kb/{docId}/docs/chunk-preview")
    public Result<List<ChunkPreview>> previewChunks(
            @PathVariable("docId") String docId,
            @RequestParam(defaultValue = "256") int chunkSize,
            @RequestParam(defaultValue = "32") int overlap) throws Exception {

        return Result.ok(knowledgeDocumentService.previewChunks(docId, chunkSize, overlap));
    }

    /**
     * 调用 Embedding
     */
    @PostMapping("/embedding/preview")
    public Result<Map<String, Object>> previewEmbedding(@RequestParam("text") String text) {

        List<Double> vector = embeddingService.embed(text);

        return Result.ok(Map.of(
                "dimensions", vector.size(),
                "sample", vector.subList(0, Math.min(5, vector.size()))
        ));
    }

    @PostMapping("/kb/{docId}/docs/embedding-preview")
    public Result<List<ChunkEmbeddingPreview>> previewDocumentEmbedding(
            @PathVariable("docId") String docId,
            @RequestParam(defaultValue = "256") int chunkSize,
            @RequestParam(defaultValue = "32") int overlap) throws Exception {

        return Result.ok(knowledgeDocumentService.previewEmbeddings(docId, chunkSize, overlap));
    }

    @PostMapping("/kb/{docId}/docs/chunk")
    public Result<Map<String, Object>> ingest(
            @PathVariable("docId") String docId,
            @RequestParam(defaultValue = "256") int chunkSize,
            @RequestParam(defaultValue = "32") int overlap) throws Exception {

        int count = knowledgeIngestionService.ingest(docId, chunkSize, overlap);

        return Result.ok(Map.of(
                "docId", docId,
                "chunkCount", count,
                "status", "success"
        ));
    }

}
