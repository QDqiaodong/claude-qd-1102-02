package com.cafe.house.controller;

import com.cafe.house.common.ApiResponse;
import com.cafe.house.service.CleaningService;
import com.cafe.house.service.CleaningService.ClaimRequest;
import com.cafe.house.service.CleaningService.CleaningDashboard;
import com.cafe.house.service.CleaningService.CleaningLogView;
import com.cafe.house.service.CleaningService.CleaningTaskView;
import com.cafe.house.service.CleaningService.CompleteRequest;
import com.cafe.house.service.CleaningService.SaveReasonRequest;
import com.cafe.house.service.CleaningService.WithdrawRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 清洁交接接口：
 * <pre>
 * 清洁人员：GET  /api/cleanings/queue              当前清洁队列（待接单 + 清洁中）
 *           POST /api/cleanings/{id}/claim          接单（责任人、开始时间、预计完成时间）
 *           POST /api/cleanings/{id}/save-reason    暂存未完成原因（刷新后保留）
 *           POST /api/cleanings/{id}/complete       完成清洁（桌台回空闲，可开台）
 *           POST /api/cleanings/{id}/withdraw       撤回清洁（回待接单，原因保留）
 * 管理员：  GET  /api/cleanings/dashboard           当前队列 + 最近交接记录
 *           GET  /api/cleanings                     全部清洁单（含已完成历史）
 *           GET  /api/cleanings/{id}                清洁单详情
 *           GET  /api/cleanings/{id}/logs           单张清洁单的交接流水
 *           GET  /api/cleanings/table/{tableId}/logs 某桌台的历史交接记录
 * 收银员：  GET  /api/cleanings/table/{tableId}/latest 桌台当前清洁单（看拦截原因）
 * </pre>
 */
@RestController
@RequestMapping("/api/cleanings")
public class CleaningController {

    @Autowired
    private CleaningService service;

    // ---------------- 清洁人员 / 管理员共用：队列 ----------------

    @GetMapping("/queue")
    public ApiResponse<List<CleaningTaskView>> queue() {
        return ApiResponse.success(service.queue());
    }

    // ---------------- 管理员看板 ----------------

    @GetMapping("/dashboard")
    public ApiResponse<CleaningDashboard> dashboard() {
        return ApiResponse.success(service.dashboard());
    }

    @GetMapping
    public ApiResponse<List<CleaningTaskView>> list() {
        return ApiResponse.success(service.listAll());
    }

    @GetMapping("/table/{tableId}/latest")
    public ApiResponse<CleaningTaskView> latestByTable(@PathVariable Long tableId) {
        return ApiResponse.success(service.latestByTable(tableId));
    }

    @GetMapping("/table/{tableId}/logs")
    public ApiResponse<List<CleaningLogView>> logsOfTable(@PathVariable Long tableId) {
        return ApiResponse.success(service.logsOfTable(tableId));
    }

    @GetMapping("/{id}")
    public ApiResponse<CleaningTaskView> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }

    @GetMapping("/{id}/logs")
    public ApiResponse<List<CleaningLogView>> logsOfTask(@PathVariable Long id) {
        return ApiResponse.success(service.logsOfTask(id));
    }

    // ---------------- 清洁人员动作 ----------------

    /** 接单：body 带 cleaner（责任人）与 etaAt（预计完成时间，yyyy-MM-ddTHH:mm） */
    @PostMapping("/{id}/claim")
    public ApiResponse<CleaningTaskView> claim(@PathVariable Long id, @RequestBody ClaimRequest req) {
        return ApiResponse.success(service.claim(id, req));
    }

    /** 暂存未完成原因：body 带 cleaner 与 reason */
    @PostMapping("/{id}/save-reason")
    public ApiResponse<CleaningTaskView> saveReason(@PathVariable Long id, @RequestBody SaveReasonRequest req) {
        return ApiResponse.success(service.saveReason(id, req));
    }

    /** 完成清洁：body 带 cleaner；成功后桌台回空闲 */
    @PostMapping("/{id}/complete")
    public ApiResponse<CleaningTaskView> complete(@PathVariable Long id, @RequestBody CompleteRequest req) {
        return ApiResponse.success(service.complete(id, req));
    }

    /** 撤回清洁：body 带 cleaner；清洁单回待接单，未完成原因保留 */
    @PostMapping("/{id}/withdraw")
    public ApiResponse<CleaningTaskView> withdraw(@PathVariable Long id, @RequestBody WithdrawRequest req) {
        return ApiResponse.success(service.withdraw(id, req));
    }
}
