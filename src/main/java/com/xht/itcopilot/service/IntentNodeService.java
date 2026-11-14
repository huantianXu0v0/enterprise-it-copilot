package com.xht.itcopilot.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.xht.itcopilot.entity.*;
import com.xht.itcopilot.mapper.IntentNodeMapper;
import com.xht.itcopilot.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class IntentNodeService extends ServiceImpl<IntentNodeMapper, IntentNodeDO> {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public String create(IntentNodeCreateRequest request) {

        require(request != null, "请求不能为空");

        String code = request.getIntentCode();

        require(code != null && code.matches("[a-z][a-z0-9_-]{0,63}"),
                "intentCode 必须以小写字母开头，只能包含小写字母、数字、下划线和短横线，最多64字符");

        require(StringUtils.hasText(request.getName())
                        && request.getName().length() <= 64,
                "名称不能为空，且不能超过64字符");

        require(request.getDescription() == null
                        || request.getDescription().length() <= 512,
                "描述不能超过512字符");

        long count = lambdaQuery()
                .eq(IntentNodeDO::getIntentCode, code)
                .eq(IntentNodeDO::getDeleted, 0)
                .count();

        if (count > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "intentCode 已存在");
        }

        // 没有父节点就是第0层；否则在父节点层级上加1。
        int level = 0;
        String parentCode = null;

        if (StringUtils.hasText(request.getParentCode())) {
            parentCode = request.getParentCode().strip();

            IntentNodeDO parent = lambdaQuery()
                    .eq(IntentNodeDO::getIntentCode, parentCode)
                    .eq(IntentNodeDO::getDeleted, 0)
                    .eq(IntentNodeDO::getEnabled, 1)
                    .one();

            require(parent != null, "父节点不存在或未启用");
            require(parent.getLevel() != null
                            && parent.getLevel() >= 0
                            && parent.getLevel() < 2,
                    "父节点层级不合法，最多支持三层");

            level = parent.getLevel() + 1;
        }

        List<String> examples = request.getExamples() == null
                ? List.of() : request.getExamples();

        require(examples.size() <= 20, "示例问题最多20个");

        for (String example : examples) {
            require(StringUtils.hasText(example) && example.length() <= 512,
                    "示例问题不能为空，且每条不能超过512字符");
        }

        IntentNodeDO node = new IntentNodeDO();
        node.setIntentCode(code);
        node.setName(request.getName().strip());
        node.setParentCode(parentCode);
        node.setLevel(level);
        node.setDescription(request.getDescription());
        node.setExamples(jsonMapper.writeValueAsString(examples));

        if (level < 2) {
            // 中间节点仅用于组织目录，不参与动作执行。
            require(request.getKind() == null
                            && !StringUtils.hasText(request.getKbId())
                            && !StringUtils.hasText(request.getMcpToolId()),
                    "第0、1层只配置分类，第2层再配置执行动作");

            node.setKind(0);
        } else {
            configureAction(node, request);
        }

        LocalDateTime now = LocalDateTime.now();
        node.setSortOrder(0);
        node.setEnabled(1);
        node.setDeleted(0);
        node.setCreateBy("local");
        node.setUpdateBy("local");
        node.setCreateTime(now);
        node.setUpdateTime(now);

        try {
            if (!save(node)) {
                throw new IllegalStateException("意图节点保存失败");
            }
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "意图节点标识重复", exception);
        }

        return node.getId();
    }

    private void configureAction(
            IntentNodeDO node,
            IntentNodeCreateRequest request) {

        Integer kind = request.getKind();

        require(kind != null && kind >= 0 && kind <= 2,
                "叶子节点必须配置 kind：0=RAG，1=系统交互，2=MCP");
        require(StringUtils.hasText(request.getDescription()),
                "叶子节点需要描述，供模型理解匹配范围");

        node.setKind(kind);

        if (kind == 0) {
            require(StringUtils.hasText(request.getKbId()),
                    "RAG节点必须配置 kbId");
            require(!StringUtils.hasText(request.getMcpToolId()),
                    "RAG节点不能同时绑定工具");

            KnowledgeBaseDO kb =
                    knowledgeBaseMapper.selectById(request.getKbId());

            require(kb != null && Integer.valueOf(0).equals(kb.getDeleted()),
                    "绑定的知识库不存在或已删除");

            node.setKbId(kb.getId());
            node.setCollectionName(kb.getCollectionName());

        } else if (kind == 1) {
            require(!StringUtils.hasText(request.getKbId())
                            && !StringUtils.hasText(request.getMcpToolId()),
                    "系统交互节点不绑定知识库或工具");

        } else {
            require(!StringUtils.hasText(request.getKbId()),
                    "MCP节点不能同时绑定知识库");
            require(StringUtils.hasText(request.getMcpToolId())
                            && request.getMcpToolId().length() <= 128,
                    "MCP节点必须配置工具标识，最多128字符");

            // 当前只登记配置，接入MCP工具目录后再校验工具是否存在。
            node.setMcpToolId(request.getMcpToolId().strip());
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, message);
        }
    }
}