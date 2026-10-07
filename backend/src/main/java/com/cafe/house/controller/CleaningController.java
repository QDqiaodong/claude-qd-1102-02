package com.cafe.house.controller;

import com.cafe.house.common.ApiResponse;
import com.cafe.house.service.CleaningService;
import com.cafe.house.service.CleaningService.AcceptCleaningRequest;
import com.cafe.house.service.CleaningService.CleaningOverview;
import com.cafe.house.service.CleaningService.CompleteCleaningRequest;
import com.cafe.house.service.CleaningService.SaveReasonRequest;
import com.cafe.house.service.CleaningService.TaskView;
import com.cafe.house.service.CleaningService.WithdrawCleaningRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cleaning")
public class CleaningController {

    @Autowired
    private CleaningService service;

    /** 当前清洁队列，收银员/清洁人员/管理员都可查看。 */
    @GetMapping("/queue")
    public ApiResponse<List<TaskView>> queue() {
        return ApiResponse.success(service.queue());
    }

    /** 管理员总览：当前队列 + 最近 20 条不可变交接流水。 */
    @GetMapping("/overview")
    public ApiResponse<CleaningOverview> overview() {
        return ApiResponse.success(service.overview());
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<TaskView> detail(@PathVariable Long id) {
        return ApiResponse.success(service.taskDetail(id));
    }

    @PostMapping("/tasks/{id}/accept")
    public ApiResponse<TaskView> accept(@PathVariable Long id, @RequestBody AcceptCleaningRequest req) {
        return ApiResponse.success(service.accept(id, req));
    }

    @PostMapping("/tasks/{id}/reason")
    public ApiResponse<TaskView> saveReason(@PathVariable Long id, @RequestBody SaveReasonRequest req) {
        return ApiResponse.success(service.saveReason(id, req));
    }

    @PostMapping("/tasks/{id}/complete")
    public ApiResponse<TaskView> complete(@PathVariable Long id, @RequestBody CompleteCleaningRequest req) {
        return ApiResponse.success(service.complete(id, req));
    }

    @PostMapping("/tasks/{id}/withdraw")
    public ApiResponse<TaskView> withdraw(@PathVariable Long id, @RequestBody WithdrawCleaningRequest req) {
        return ApiResponse.success(service.withdraw(id, req));
    }
}
