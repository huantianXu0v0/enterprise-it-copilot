package com.xht.itcopilot.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.xht.itcopilot.entity.KnowledgeBaseDO;
import com.xht.itcopilot.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBaseDO> {

    public String create(String kbName, String collection_name, String embeddingModel) {

        if (!StringUtils.hasText(kbName)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "知识库名称不能为空");
        }
        if (!StringUtils.hasText(embeddingModel)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Embedding 模型不能为空");
        }

        String name = kbName.trim();
        String model = embeddingModel.trim();

        if (name.length() > 128 || model.length() > 64) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "知识库名称最多128字符，模型标识最多64字符");
        }

        // 提前生成雪花 ID，用它构造唯一的 collectionName。
        String kbId = IdWorker.getIdStr();

        String collectionName = "kb-" + collection_name;

        KnowledgeBaseDO knowledgeBase = new KnowledgeBaseDO();
        knowledgeBase.setId(kbId);
        knowledgeBase.setName(name);
        knowledgeBase.setEmbeddingModel(model);
        knowledgeBase.setCollectionName(collectionName);
        knowledgeBase.setCreatedBy("local");
        knowledgeBase.setDeleted(0);

        boolean success = save(knowledgeBase);

        if (!success) {
            throw new IllegalStateException("知识库创建失败");
        }

        log.info("知识库创建成功：kbId={}, name={}, collectionName={}, model={}",
                kbId, name, collectionName, model);

        return kbId;
    }

}
