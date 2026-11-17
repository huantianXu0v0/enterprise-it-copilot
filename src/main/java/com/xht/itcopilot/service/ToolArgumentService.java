package com.xht.itcopilot.service;

import com.xht.itcopilot.entity.ConversationMemory;
import com.xht.itcopilot.entity.RoutePlan;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

// 拿到工具 InputSchema - 调用模型提取arguments - 检查参数名/字符串类型/必填项 -
//         完整才调用工具 , 缺失返回 needs_input, 询问用户
@Service
@RequiredArgsConstructor
public class ToolArgumentService {
    private final ChatModelService model;

    // 当前三个工具仅使用扁平字符串参数；不是完整 JSON Schema 验证器。
    public PreparedArguments prepare(Tool tool, RoutePlan plan, ConversationMemory memory) {
        var schema = tool.inputSchema();
        if (schema == null || !"object".equals(schema.type())) {
            throw invalid("暂不支持该工具的参数结构");
        }
        Map<String, Object> properties = schema.properties() == null
                ? Map.of() : schema.properties();
        List<String> required = schema.required() == null ? List.of() : schema.required();
        if (!properties.keySet().containsAll(required)) {
            throw invalid("工具的必填参数定义不完整");
        }
        for (Object property : properties.values()) {
            if (!(property instanceof Map<?, ?> definition)
                    || !"string".equals(definition.get("type"))) {
                throw invalid("当前执行器只支持扁平字符串参数");
            }
        }

        // 无参数工具直接执行，不为它额外调用大模型。
        Map<String, Object> extracted = properties.isEmpty() ? Map.of()
                : model.extractToolArguments(plan.getOriginalQuestion(),
                plan.getRewrittenQuestion(), tool, memory);
        Map<String, Object> arguments = new LinkedHashMap<>(extracted);
        for (String name : arguments.keySet()) {
            if (!properties.containsKey(name)) {
                throw invalid("模型返回了工具未声明的参数");
            }
        }
        for (String name : new ArrayList<>(arguments.keySet())) {
            Object value = arguments.get(name);
            if (value == null || (value instanceof String text && text.isBlank())) {
                arguments.remove(name);
                continue;
            }
            if (!(value instanceof String text)) {
                throw invalid("模型返回的工具参数类型不正确");
            }
            Map<?, ?> definition = (Map<?, ?>) properties.get(name);
            String normalized = text.strip();
            if (definition.get("enum") instanceof List<?> allowed
                    && !allowed.contains(normalized)) {
                throw invalid("模型返回的工具参数不在允许范围内");
            }
            arguments.put(name, normalized);
        }
        List<String> missing = required.stream()
                .filter(name -> !arguments.containsKey(name)).toList();
        return new PreparedArguments(arguments, missing);
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    @Data
    @AllArgsConstructor
    public static class PreparedArguments {
        private Map<String, Object> arguments;
        private List<String> missing;
    }
}
