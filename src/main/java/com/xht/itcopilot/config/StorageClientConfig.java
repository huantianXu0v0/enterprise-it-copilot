package com.xht.itcopilot.config;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
public class StorageClientConfig {

    /*
        创建 S3客户端,用来建立连接 文件存储服务 MiniIo 或者 RustFS
        endpoint : 连接服务地址
        accessKey : 账号
        secreKey : 密码
     */
    @Bean
    public S3Client s3Client(
            @Value("${rag.storage.s3.endpoint}") String endpoint,
            @Value("${rag.storage.s3.access-key}") String accessKey,
            @Value("${rag.storage.s3.secret-key}") String secretKey
            ){
        // 构建 S3客户端
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.AP_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey,secretKey)))
                .forcePathStyle(true)
                .build();
    }
}
