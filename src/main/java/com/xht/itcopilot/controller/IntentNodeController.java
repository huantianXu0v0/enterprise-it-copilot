package com.xht.itcopilot.controller;

import com.xht.itcopilot.entity.*;
import com.xht.itcopilot.service.IntentNodeService;
import com.xht.itcopilot.service.IntentRecognitionService;
import com.xht.itcopilot.service.IntentTreeCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;


@RestController
@RequiredArgsConstructor
@RequestMapping("/intent/nodes")
public class IntentNodeController {

    private final IntentNodeService intentNodeService;
    private final IntentTreeCacheService cacheService;
    private final IntentRecognitionService recognitionService;

    // 新增意图节点
    @PostMapping
    public Result<Map<String, String>> create(@RequestBody IntentNodeCreateRequest request) {

        String id = intentNodeService.create(request);

        // 保存后, 删除 旧缓存
        cacheService.evict();

        return Result.ok(Map.of("id", id));
    }

    // 查询意图节点
    @GetMapping("/tree")
    public Result<List<IntentTreeNode>> tree(){
        return Result.ok(intentNodeService.buildTree());
    }

    //
    @DeleteMapping("/tree/cache")
    public Result<Void> clearCache() {
        if (!cacheService.evict()) {
            return Result.fail("缓存清除失败，请检查Redis");
        }
        return Result.ok();
    }

    @PostMapping("/recognize/{conversationId}")
    public Result<IntentRecognitionResult> recognize(
            @PathVariable("conversationId") String conversationId,
            @RequestBody RagQueryRequest request) {

        return Result.ok(
                recognitionService.recognize(conversationId, request));
    }

 }