package com.xht.itcopilot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xht.itcopilot.entity.KnowledgeDocumentDO;
import com.xht.itcopilot.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.sql.Wrapper;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocumentService {

    private final KnowledgeDocumentMapper documentMapper;
    private final FileStorageService fileStorageService;

    public String upload(String kbId, MultipartFile file)
            throws IOException {

        String collectionName = documentMapper.findCollectionName(kbId);
        log.debug(collectionName + "  !!!!");
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
}
