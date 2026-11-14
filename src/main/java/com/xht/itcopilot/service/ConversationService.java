package com.xht.itcopilot.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.xht.itcopilot.entity.ConversationDO;
import com.xht.itcopilot.mapper.ConversationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
}
