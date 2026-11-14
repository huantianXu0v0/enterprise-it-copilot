package com.xht.itcopilot.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.xht.itcopilot.entity.ConversationDO;
import com.xht.itcopilot.entity.ConversationMessageDO;
import com.xht.itcopilot.mapper.ConversationMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationMessageService extends ServiceImpl<ConversationMessageMapper, ConversationMessageDO> {
    private final ConversationService conversationService;

    @Transactional(rollbackFor = Exception.class)
    public void saveCompletedTurn(String conversationId, String question, String answer) {

        if (!StringUtils.hasText(question)
                || !StringUtils.hasText(answer)) {
            throw new IllegalArgumentException("问题和回答不能为空");
        }

        // 生成期间会话可能被删除，保存时再次校验。
        conversationService.checkConversation(conversationId);

        LocalDateTime now = LocalDateTime.now();

        ConversationMessageDO userMessage = buildMessage(conversationId, "user", question, now);

        if (!save(userMessage)) {
            throw new IllegalStateException("用户消息保存失败");
        }

        ConversationMessageDO assistantMessage = buildMessage(conversationId, "assistant", answer, now);

        // save 后 MyBatis-Plus 已把主键回填到 userMessage。
        assistantMessage.setReplyToMessageId(userMessage.getId());

        if (!save(assistantMessage)) {
            throw new IllegalStateException("助手消息保存失败");
        }

        boolean updated = conversationService.lambdaUpdate()
                .eq(ConversationDO::getConversationId, conversationId)
                .eq(ConversationDO::getUserId, "local")
                .eq(ConversationDO::getDeleted, 0)
                .set(ConversationDO::getLastTime, now)
                .set(ConversationDO::getUpdateTime, now)
                .update();

        if (!updated) {
            throw new IllegalStateException("会话更新时间失败");
        }

//        log.info("本轮消息已写入事务：conversationId={}, userMessageId={}, assistantMessageId={}",
//                conversationId, userMessage.getId(), assistantMessage.getId());
    }

    private ConversationMessageDO buildMessage(
            String conversationId,
            String role,
            String content,
            LocalDateTime now) {

        ConversationMessageDO message = new ConversationMessageDO();
        message.setConversationId(conversationId);
        message.setUserId("local");
        message.setRole(role);
        message.setContent(content);
        message.setMessageStatus("NORMAL");
        message.setCreateTime(now);
        message.setUpdateTime(now);
        message.setDeleted(0);

        return message;
    }
    /** 仅用于界面预览最近5轮；模型上下文使用 loadUnsummarizedHistory。 */
    public List<ConversationMessageDO> loadRecentHistory(String conversationId) {
        conversationService.checkConversation(conversationId);
        List<ConversationMessageDO> assistants = normalMessages(conversationId)
                .eq(ConversationMessageDO::getRole, "assistant")
                .isNotNull(ConversationMessageDO::getReplyToMessageId)
                .orderByDesc(ConversationMessageDO::getCreateTime)
                .orderByDesc(ConversationMessageDO::getId)
                .last("LIMIT 5").list();
        Collections.reverse(assistants);
        return pairQuestions(conversationId, assistants);
    }

    public List<ConversationMessageDO> loadUnsummarizedHistory(
            String conversationId, String lastMessageId) {
        conversationService.checkConversation(conversationId);
        var query = normalMessages(conversationId)
                .eq(ConversationMessageDO::getRole, "assistant")
                .isNotNull(ConversationMessageDO::getReplyToMessageId);
        if (lastMessageId != null) {
            ConversationMessageDO anchor = normalMessages(conversationId)
                    .eq(ConversationMessageDO::getId, lastMessageId)
                    .eq(ConversationMessageDO::getRole, "assistant").one();
            if (anchor == null || anchor.getCreateTime() == null) {
                throw new IllegalStateException("摘要进度对应的原始消息不存在，请检查会话数据");
            }
            // 与窗口排序保持一致：先时间，再用ID解决同一时间的排序。
            query.and(w -> w.gt(ConversationMessageDO::getCreateTime, anchor.getCreateTime())
                    .or(x -> x.eq(ConversationMessageDO::getCreateTime, anchor.getCreateTime())
                            .gt(ConversationMessageDO::getId, anchor.getId())));
        }
        return pairQuestions(conversationId, query
                .orderByAsc(ConversationMessageDO::getCreateTime)
                .orderByAsc(ConversationMessageDO::getId).list());
    }

    private com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<ConversationMessageDO>
            normalMessages(String conversationId) {
        return lambdaQuery()
                .eq(ConversationMessageDO::getConversationId, conversationId)
                .eq(ConversationMessageDO::getUserId, "local")
                .eq(ConversationMessageDO::getDeleted, 0)
                .eq(ConversationMessageDO::getMessageStatus, "NORMAL");
    }

    private List<ConversationMessageDO> pairQuestions(
            String conversationId, List<ConversationMessageDO> assistants) {
        if (assistants.isEmpty()) return List.of();
        List<String> ids = assistants.stream().map(ConversationMessageDO::getReplyToMessageId)
                .distinct().toList();
        Map<String, ConversationMessageDO> questions = new HashMap<>();
        for (ConversationMessageDO question : normalMessages(conversationId)
                .eq(ConversationMessageDO::getRole, "user")
                .in(ConversationMessageDO::getId, ids).list()) {
            questions.put(question.getId(), question);
        }
        List<ConversationMessageDO> result = new ArrayList<>();
        for (ConversationMessageDO assistant : assistants) {
            ConversationMessageDO question = questions.get(assistant.getReplyToMessageId());
            if (question == null || !StringUtils.hasText(question.getContent())
                    || !StringUtils.hasText(assistant.getContent())) {
                // 不跳过损坏的问答再推进摘要，否则会永久漏掉历史。
                throw new IllegalStateException("历史问答不完整，assistantId=" + assistant.getId());
            }
            result.add(question);
            result.add(assistant);
        }
        return result;
    }
}
