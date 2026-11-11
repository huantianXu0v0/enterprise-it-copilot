package com.xht.itcopilot.service;

import com.xht.itcopilot.entity.KnowledgeDocumentDO;
import com.xht.itcopilot.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectAclRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import org.springframework.util.StringUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final S3Client s3Client;

    @Value("${rag.storage.kb-bucket}")
    private String bucket;

    // 上传到 RustFS文件存储
    public String upload(String kbId, MultipartFile file) throws IOException {
        // 校验(空 - 类型)
        if(file.isEmpty()){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法-上传文件为空!");
        }
        String mimeType; // 文件类型
        try(InputStream inputStream = file.getInputStream()){
            mimeType = new Tika().detect(
                    inputStream, file.getOriginalFilename()
            );

//            String originalName = file.getOriginalFilename();
//            if (originalName != null) {
//                // 空文件
//                int dotIndex = originalName.lastIndexOf(".");
//                String fileNameNoSuffix;
//                if (dotIndex > 0) {
//                    fileNameNoSuffix = originalName.substring(0, dotIndex);
//                } else {
//                    fileNameNoSuffix = originalName;
//                }
//            }
            String key = kbId + "/" + UUID.randomUUID();

            // 建立 上传请求格式
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(mimeType)
                    .build();

            // 重新打开文件,上传完整内容
            try(InputStream stream = file.getInputStream()){
                s3Client.putObject(request,
                        RequestBody.fromInputStream(
                                stream, file.getSize()
                        )
                        );
            }
            return key;
        }

    }

    public void delete(String key) {
        s3Client.deleteObject(request ->
                request.bucket(bucket).key(key));
    }

    public InputStream openStream(String fileUrl) {

        return s3Client.getObject(request -> request.bucket(bucket).key(fileUrl));
    }

}
