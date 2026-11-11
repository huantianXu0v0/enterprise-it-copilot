package com.xht.itcopilot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class EnterpriseItCopilotApplicationTests {
    // JDBC
    @Autowired
    private DataSource dataSource;

    // S3客户端
    @Autowired
    private S3Client s3Client;


    @Test
            /*
                   测试连接 postgreSQL 数据库
             */
    void databaseConnection() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertTrue(connection.isValid(5));
        }
    }

    /**
     * 测试 S3文档存储 服务
     */
    @Test
    void rustFsConnection(){
        ListBucketsResponse listBucketsResponse = s3Client.listBuckets();
        listBucketsResponse.buckets().forEach(
                bucket -> {
                    System.out.println(bucket.name());
                }
        );
    }


    @Test
    void contextLoads() {
    }



}
