package com.xht.itcopilot.controller;

import com.xht.itcopilot.entity.IntentNodeCreateRequest;
import com.xht.itcopilot.entity.IntentTreeNode;
import com.xht.itcopilot.entity.Result;
import com.xht.itcopilot.service.IntentNodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;


@RestController
@RequiredArgsConstructor
@RequestMapping("/intent/nodes")
public class IntentNodeController {

    private final IntentNodeService intentNodeService;
    // 新增意图节点
    @PostMapping
    public Result<Map<String, String>> create(@RequestBody IntentNodeCreateRequest request) {

        String id = intentNodeService.create(request);

        return Result.ok(Map.of("id", id));
    }

    // 查询意图节点
    @GetMapping("/tree")
    public Result<List<IntentTreeNode>> tree(){
        return Result.ok(intentNodeService.buildTree());
    }
 }