package com.xht.itcopilot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xht.itcopilot.entity.ChunkEmbeddingPreview;
import com.xht.itcopilot.entity.ChunkPreview;
import com.xht.itcopilot.entity.KnowledgeDocumentDO;
import com.xht.itcopilot.mapper.KnowledgeDocumentMapper;
import com.xht.itcopilot.rag.StructuredTextSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.xml.sax.SAXException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.sql.Wrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocumentService {

    private final KnowledgeDocumentMapper documentMapper;

    private final FileStorageService fileStorageService;

    private final StructuredTextSplitter structuredTextSplitter;

    private final EmbeddingService embeddingService;

    public String upload(String kbId, MultipartFile file)
            throws IOException {

        String collectionName = documentMapper.findCollectionName(kbId);
        if (collectionName == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "知识库不存在");
        }

        if (file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "上传文件不能为空");
        }

        String filename = file.getOriginalFilename();
        if (!StringUtils.hasText(filename)) {
            filename = "document";
        }

        String mimeType;
        try (var input = file.getInputStream()) {
            mimeType = new Tika().detect(input, filename);
        }

        String extension = StringUtils.getFilenameExtension(filename);

        var document = new KnowledgeDocumentDO();
        document.setKbId(kbId);
        document.setDocName(filename);
        document.setFileType(extension == null
                ? "bin" : extension.toLowerCase(Locale.ROOT));
        document.setMimeType(mimeType);
        document.setFileSize(file.getSize());
        document.setStatus("pending");
        document.setChunkCount(0);
        document.setEnabled(1);
        document.setCreatedBy("local");

        // 用 collection_name 作为目录前缀。
        String key = fileStorageService.upload(collectionName, file);
        document.setFileUrl(key);

        try {
            if (documentMapper.insert(document) != 1) {
                throw new IllegalStateException("文档记录保存失败");
            }
        } catch (RuntimeException exception) {
            try {
                fileStorageService.delete(key);
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }

        // MyBatis-Plus 自动生成的 ID 已回填到实体。
        return document.getId();
    }

    public String parse(String docId) throws TikaException, IOException, SAXException {

        KnowledgeDocumentDO document = documentMapper.selectById(docId);

        if(document == null){
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }

        // 取 file_url, 从RustFS 取文档内容 由 Tika 解析
//        log.info("开始解析文档，docId={}, key={}",
//                docId, document.getFileUrl());

        // Tika 解析器
        var parser = new AutoDetectParser();
        var handler = new ToXMLContentHandler();
        var metadata = new Metadata();

        // 对象 key 没有扩展名，提供原始文件名辅助类型识别。
        metadata.set(
                TikaCoreProperties.RESOURCE_NAME_KEY,
                document.getDocName());

        // 3. 从 RustFS 读取，解析完成后关闭流。
        try (var input = new BufferedInputStream(
                fileStorageService.openStream(document.getFileUrl()))) {

            parser.parse(input, handler, metadata, new ParseContext());

            String xhtml = handler.toString();
//            log.info("文档解析完成，docId={}, 输出字符数={}",
//                    docId, xhtml.length());

            return xhtml;
        } catch (Exception exception) {
            log.error("文档解析失败，docId={}", docId, exception);
            throw exception;
        }

    }

    public List<ChunkPreview> previewChunks(String docId, int chunkSize, int overlap) throws Exception {

        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "要求 chunkSize > 0，且 0 <= overlap < chunkSize");
        }

        // 复用：查文档 → 读取 RustFS → Tika 解析为 XHTML。
        String xhtml = parse(docId);

        List<ChunkPreview> chunks = structuredTextSplitter.split(xhtml, chunkSize, overlap);

        for (ChunkPreview chunk : chunks) {
//            log.info("分块完成：docId={}, index={}, type={}, chars={}",
//                    docId, chunk.getChunkIndex(),
//                    chunk.getType(), chunk.getContent().length());

            if (!"TEXT".equals(chunk.getType()) && chunk.getContent().length() > chunkSize) {
                log.warn("特殊块超过目标长度，整体保留：docId={}, index={}",
                        docId, chunk.getChunkIndex());
            }
        }

        return chunks;
    }

    public List<ChunkEmbeddingPreview> previewEmbeddings(String docId, int chunkSize, int overlap) throws Exception {

        List<ChunkPreview> chunks = previewChunks(docId, chunkSize, overlap);

        if (chunks.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "文档没有可向量化的内容");
        }

        // 先检查所有块，再开始调用模型。
        // 与当前 EmbeddingService 的测试限制保持一致。
        for (ChunkPreview chunk : chunks) {
            if (!StringUtils.hasText(chunk.getContent())
                    || chunk.getContent().length() > 1024) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "分块 " + chunk.getChunkIndex()
                                + " 内容为空或超过当前1024字符限制，类型："
                                + chunk.getType());
            }
        }

        List<ChunkEmbeddingPreview> results = new ArrayList<>();

        // 第一版逐块调用，方便观察每块的执行情况。
        for (ChunkPreview chunk : chunks) {
//            log.info("开始分块向量化：docId={}, index={}, type={}",
//                    docId, chunk.getChunkIndex(), chunk.getType());

            List<Double> vector = embeddingService.embed(chunk.getContent());

            results.add(new ChunkEmbeddingPreview(
                    chunk.getChunkIndex(),
                    chunk.getType(),
                    vector.size(),
                    new ArrayList<>(
                            vector.subList(0, Math.min(5, vector.size())))
            ));
        }

        log.info("文档向量化预览完成：docId={}, chunkCount={}",
                docId, results.size());

        return results;
    }
}
