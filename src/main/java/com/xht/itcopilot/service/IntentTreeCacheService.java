package com.xht.itcopilot.service;

import com.xht.itcopilot.entity.IntentTreeNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentTreeCacheService {

    private static final String KEY = "itcopilot:intent-tree:v2";

    private final StringRedisTemplate redisTemplate;
    private final IntentNodeService intentNodeService;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public synchronized List<IntentTreeNode> getTree() {

        try {
            String json = redisTemplate.opsForValue().get(KEY);

            if (StringUtils.hasText(json)) {
                IntentTreeNode[] nodes =
                        jsonMapper.readValue(json, IntentTreeNode[].class);

                if (nodes != null) {
                    log.info("意图树缓存命中");
                    return Arrays.asList(nodes);
                }
            }
        } catch (DataAccessException | JacksonException exception) {
            log.warn("意图树缓存读取失败，回退数据库：errorType={}",
                    exception.getClass().getSimpleName());
        }

        return refreshTree();
    }

    public synchronized List<IntentTreeNode> refreshTree() {
        List<IntentTreeNode> tree = intentNodeService.buildTree();

        String json = jsonMapper.writeValueAsString(tree);

        try {
            redisTemplate.opsForValue().set(
                    KEY, json, Duration.ofMinutes(5));

            log.info("意图树已写入Redis");
        } catch (DataAccessException exception) {
            log.warn("Redis写入失败，本次继续使用数据库结果");
        }

        return tree;
    }

    public synchronized boolean evict() {
        try {
            redisTemplate.delete(KEY);
            return true;
        } catch (DataAccessException exception) {
            log.warn("意图树缓存清除失败，旧缓存将在TTL到期后失效");
            return false;
        }
    }
}