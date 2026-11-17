package com.xht.itcopilot.service;

import com.xht.itcopilot.entity.McpCallRequest;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolService {

    private final McpSyncClient mcpClient;
    // 返回对应 服务 的工具列表
    public List<McpSchema.Tool> listTools() {
        checkCancelled();

        try {
            // 当前示例只有三个工具，工具列表为单页。
            return mcpClient.listTools().tools();

        } catch (RuntimeException exception) {
            checkCancelled();

            log.error("获取MCP工具列表失败", exception);

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "获取工具列表失败，请检查工具服务",
                    exception);
        }
    }

    public McpSchema.CallToolResult call(McpCallRequest request) {
        checkCancelled();

        if (request == null || !StringUtils.hasText(request.getToolName())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "工具名称不能为空");
        }

        String toolName = request.getToolName().strip();

        // 工具必须真实存在，不能直接信任调用方提供的名称。
        boolean exists = listTools().stream().anyMatch(tool -> toolName.equals(tool.name()));

        if (!exists) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "工具不存在：" + toolName);
        }

        Map<String, Object> arguments = request.getArguments() == null ? Map.of() : request.getArguments();

        log.info("开始调用MCP工具：toolName={}", toolName);

        McpSchema.CallToolResult result;

        try {
            result = mcpClient.callTool(
                    new McpSchema.CallToolRequest(
                            toolName,
                            arguments
                    )
            );

        } catch (RuntimeException exception) {
            checkCancelled();

            log.error("MCP工具调用异常：toolName={}",
                    toolName, exception);

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "工具调用失败或超时",
                    exception);
        }

        checkCancelled();

        if (result == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "工具服务没有返回结果");
        }

        log.info("MCP工具调用结束：toolName={}, isError={}",
                toolName, result.isError());

        // 保留工具执行状态，方便后续路由层区分成功与失败。
        return result;
    }

    private void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("工具调用已取消");
        }
    }
}