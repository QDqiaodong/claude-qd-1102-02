package com.cafe.house.controller;

import com.cafe.house.common.ApiResponse;
import com.cafe.house.entity.TableInfo;
import com.cafe.house.service.TableInfoService;

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
@RequestMapping("/api/table-infos")
public class TableInfoController {

    @Autowired
    private TableInfoService service;

    @GetMapping
    public ApiResponse<List<TableInfo>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(service.list(status));
    }

    @PostMapping
    public ApiResponse<TableInfo> create(@RequestBody TableInfo input) {
        return ApiResponse.success(service.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<TableInfo> update(@PathVariable Long id, @RequestBody TableInfo input) {
        return ApiResponse.success(service.update(id, input));
    }

    @PostMapping("/{id}/open")
    public ApiResponse<TableInfo> open(@PathVariable Long id) {
        return ApiResponse.success(service.open(id));
    }

    @PostMapping("/{id}/clean")
    public ApiResponse<TableInfo> clean(@PathVariable Long id) {
        return ApiResponse.success(service.clean(id));
    }
}
