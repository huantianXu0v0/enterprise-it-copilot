package com.xht.itcopilot.controller;

import com.xht.itcopilot.entity.Result;
import com.xht.itcopilot.service.FileStorageService;
import com.xht.itcopilot.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeDocumentService knowledgeDocumentService;

    /**
     * 上传文件或导入 URL，保存文件并登记文档，返回 docId。
     */
    @PostMapping(value = "/kb/{kbId}/docs/upload",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result upload(@PathVariable("kbId") String kbId, @RequestPart("file") MultipartFile file) throws IOException {
        // 文件上传后 返回 对象key

        String key = knowledgeDocumentService.upload(kbId, file);
        return Result.ok(Map.of("fileUrl", key));
    }

    /**
     *  文档 切块
     *
     */
}
