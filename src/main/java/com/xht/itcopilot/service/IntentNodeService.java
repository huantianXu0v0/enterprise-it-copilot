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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class IntentNodeService extends ServiceImpl<IntentNodeMapper, IntentNodeDO> {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final McpToolService mcpToolService;

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

        if (request.getKind() == null) {
            require(level < 2, "第2层必须配置执行动作");

            require(!StringUtils.hasText(request.getKbId())
                            && !StringUtils.hasText(request.getMcpToolId()),
                    "分类节点不能绑定知识库或工具");

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

    public void updateBinding(String code, IntentNodeCreateRequest request) {
        require(request != null,"请求不能为空");
        IntentNodeDO node = lambdaQuery().eq(IntentNodeDO::getIntentCode,code)
                .eq(IntentNodeDO::getDeleted,0).one();
        require(node != null,"意图节点不存在");
        require(!lambdaQuery().eq(IntentNodeDO::getParentCode,code)
                .eq(IntentNodeDO::getDeleted,0).exists(),"只能修改叶子节点的资源绑定");
        if (!StringUtils.hasText(request.getDescription())) request.setDescription(node.getDescription());
        configureAction(node,request);
        node.setDescription(request.getDescription());
        require(node.getDescription().length()<=512,"描述不能超过512字符");
        lambdaUpdate().eq(IntentNodeDO::getId,node.getId())
                .set(IntentNodeDO::getKind,node.getKind())
                .set(IntentNodeDO::getKbId,request.getKind()==0?node.getKbId():null)
                .set(IntentNodeDO::getCollectionName,request.getKind()==0?node.getCollectionName():null)
                .set(IntentNodeDO::getMcpToolId,request.getKind()==2?node.getMcpToolId():null)
                .set(IntentNodeDO::getDescription,node.getDescription())
                .set(IntentNodeDO::getUpdateTime,LocalDateTime.now()).update();
    }

    // 校验
    private void configureAction(IntentNodeDO node, IntentNodeCreateRequest request) {

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

            require(mcpToolService.listTools().stream().anyMatch(t -> t.name().equals(request.getMcpToolId().strip())),
                    "绑定的工具不存在，请从实际工具列表中选择");
            node.setMcpToolId(request.getMcpToolId().strip());
        }
    }

    // 拒绝
    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    // JSON化 意图树
    public List<IntentTreeNode> buildTree() {

        List<IntentNodeDO> nodes = lambdaQuery()
                .eq(IntentNodeDO::getDeleted, 0)
                .eq(IntentNodeDO::getEnabled, 1)
                .orderByAsc(IntentNodeDO::getSortOrder)
                .orderByAsc(IntentNodeDO::getId)
                .list();

        // intentCode -> 树节点，方便通过 parentCode 快速找到父节点。
        Map<String, IntentTreeNode> nodeMap = new HashMap<>();

        // 第一遍：把数据库记录转换成树节点。
        for (IntentNodeDO node : nodes) {
            if (!StringUtils.hasText(node.getIntentCode())
                    || node.getLevel() == null
                    || node.getLevel() < 0
                    || node.getLevel() > 2) {

                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "意图节点编码或层级异常，节点ID：" + node.getId());
            }

            IntentTreeNode treeNode = toTreeNode(node);

            if (nodeMap.putIfAbsent(node.getIntentCode(), treeNode) != null) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "存在重复的意图编码：" + node.getIntentCode());
            }
        }

        List<IntentTreeNode> roots = new ArrayList<>();

        // 第二遍：连接父子关系。
        for (IntentNodeDO node : nodes) {
            IntentTreeNode current = nodeMap.get(node.getIntentCode());

            if (!StringUtils.hasText(node.getParentCode())) {
                if (node.getLevel() != 0) {
                    throw new ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "非根节点缺少父节点：" + node.getIntentCode());
                }

                roots.add(current);
                continue;
            }

            IntentTreeNode parent = nodeMap.get(node.getParentCode());

            if (parent == null) {
                // 父节点不存在、被删除或被禁用：
                // 当前节点不挂到任何根节点下，也不把它提升成根节点。
                continue;
            }

            if (node.getLevel() != parent.getLevel() + 1) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "父子节点层级不一致：" + node.getIntentCode());
            }

            parent.getChildren().add(current);
        }
        // 只有叶子节点才可以执行操作
        for (IntentTreeNode node : nodeMap.values()) {
            node.setExecutable(isExecutable(node));
        }
        return roots;
    }

    private IntentTreeNode toTreeNode(IntentNodeDO node) {

        IntentTreeNode result = new IntentTreeNode();
        result.setIntentCode(node.getIntentCode());
        result.setName(node.getName());
        result.setLevel(node.getLevel());
        result.setDescription(node.getDescription());

        // 数据库中的 examples 是 JSON 字符串，返回时转成真正的数组。
        if (StringUtils.hasText(node.getExamples())) {
            try {
                JsonNode array = jsonMapper.readTree(node.getExamples());

                if (array == null || !array.isArray()) {
                    throw new IllegalArgumentException("examples 必须是JSON数组");
                }

                List<String> examples = new ArrayList<>();

                for (JsonNode item : array) {
                    if (!item.isString()) {
                        throw new IllegalArgumentException("示例问题必须是字符串");
                    }

                    examples.add(item.asString());
                }

                result.setExamples(examples);

            } catch (JacksonException | IllegalArgumentException exception) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "示例问题格式异常，意图编码：" + node.getIntentCode(),
                        exception);
            }
        }

        result.setKind(node.getKind());
        result.setKbId(node.getKbId());
        result.setCollectionName(node.getCollectionName());
        result.setMcpToolId(node.getMcpToolId());

        return result;
    }

    private boolean isExecutable(IntentTreeNode node) {

        if (!node.getChildren().isEmpty()) {
            return false;
        }

        Integer kind = node.getKind();

        if (Integer.valueOf(0).equals(kind)) {
            return StringUtils.hasText(node.getKbId())
                    && !StringUtils.hasText(node.getMcpToolId());
        }

        if (Integer.valueOf(1).equals(kind)) {
            return !StringUtils.hasText(node.getKbId())
                    && !StringUtils.hasText(node.getMcpToolId());
        }

        if (Integer.valueOf(2).equals(kind)) {
            return StringUtils.hasText(node.getMcpToolId())
                    && !StringUtils.hasText(node.getKbId());
        }

        return false;
    }
}