package com.xht.itcopilot.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class McpClientConfig {
    // 启动时与对应的MCP服务器建立握手,应用关闭时释放连接
    @Bean(initMethod = "initialize", destroyMethod = "close")
    public McpSyncClient mcpClient(@Value("${mcp.server-url}") String serverUrl, @Value("${mcp.endpoint:/mcp}") String endpoint) {

        var transport = HttpClientStreamableHttpTransport
                .builder(serverUrl)
                .endpoint(endpoint)
                .build();
        // 返回一个 MCP客户端
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .build();
    }
}