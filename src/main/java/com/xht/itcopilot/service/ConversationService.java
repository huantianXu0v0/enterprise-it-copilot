package com.xht.itcopilot.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.xht.itcopilot.entity.ConversationDO;
import com.xht.itcopilot.mapper.ConversationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
public class ConversationService extends ServiceImpl<ConversationMapper, ConversationDO> {
    /**
     * 新增 会话
     * @return
     */
    public String createConversation() {

        String id = IdWorker.getIdStr();
        String conversationId = IdWorker.getIdStr();

        String userId = "local";

        ConversationDO conversationDO = new ConversationDO();
        conversationDO.setId(id);
        conversationDO.setConversationId(conversationId);
        conversationDO.setUserId(userId);
        conversationDO.setTitle("新对话"); // TODO 后续需要重 大模型从提炼出对应的结果

        boolean save = save(conversationDO);

        if(!save){
            throw new IllegalStateException("创建对话失败!");
        }
        return conversationId;
    }

    /**
     * 会话校验
     * 1/会话id不能为空
     * 2/会话要和用户的id一致
     */
    public void checkConversation(String conversationId){

        if(!StringUtils.hasText(conversationId)){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话ID不能为空");
        }
        // TODO 用户校验
        Long count = lambdaQuery()
                .eq(ConversationDO::getConversationId, conversationId)
                .eq(ConversationDO::getUserId, "local")
                .eq(ConversationDO::getDeleted, 0)
                .count();

        if(count == 0){
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或已删除");
        }


    }
}
