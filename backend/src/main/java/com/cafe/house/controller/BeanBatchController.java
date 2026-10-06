package com.cafe.house.controller;

import com.cafe.house.common.ApiResponse;
import com.cafe.house.entity.BeanBatch;
import com.cafe.house.service.BeanBatchService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/bean-batches")
public class BeanBatchController {

    @Autowired
    private BeanBatchService service;

    @GetMapping
    public ApiResponse<List<BeanBatch>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(service.list(status));
    }

    @PostMapping
    public ApiResponse<BeanBatch> create(@RequestBody BeanBatch input) {
        return ApiResponse.success(service.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<BeanBatch> update(@PathVariable Long id, @RequestBody BeanBatch input) {
        return ApiResponse.success(service.update(id, input));
    }

    @PostMapping("/{id}/roast")
    public ApiResponse<BeanBatch> roast(@PathVariable Long id) {
        return ApiResponse.success(service.roast(id));
    }

    @PostMapping("/{id}/finish-roast")
    public ApiResponse<BeanBatch> finishRoast(@PathVariable Long id) {
        return ApiResponse.success(service.finishRoast(id));
    }

    @PostMapping("/{id}/deplete")
    public ApiResponse<BeanBatch> deplete(@PathVariable Long id) {
        return ApiResponse.success(service.deplete(id));
    }
}
