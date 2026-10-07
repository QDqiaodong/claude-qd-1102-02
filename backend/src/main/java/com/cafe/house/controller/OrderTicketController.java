package com.cafe.house.controller;

import com.cafe.house.common.ApiResponse;
import com.cafe.house.service.OrderTicketService;
import com.cafe.house.service.OrderTicketService.AddItemRequest;
import com.cafe.house.service.OrderTicketService.CreateTicketRequest;
import com.cafe.house.service.OrderTicketService.FinishPreview;
import com.cafe.house.service.OrderTicketService.FinishTicketRequest;
import com.cafe.house.service.OrderTicketService.OrderTicketDetail;
import com.cafe.house.service.OrderTicketService.SettleTicketRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/order-tickets")
public class OrderTicketController {

    @Autowired
    private OrderTicketService service;

    @GetMapping
    public ApiResponse<List<?>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(service.list(status));
    }

    @PostMapping
    public ApiResponse<OrderTicketDetail> create(@RequestBody CreateTicketRequest req) {
        return ApiResponse.success(service.create(req));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderTicketDetail> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping("/{id}/add-item")
    public ApiResponse<OrderTicketDetail> addItem(@PathVariable Long id, @RequestBody AddItemRequest req) {
        return ApiResponse.success(service.addItem(id, req));
    }

    /** 完成前预览：本次将从哪些批次扣多少克，或为什么做不了。只读。 */
    @GetMapping("/{id}/finish-preview")
    public ApiResponse<FinishPreview> previewFinish(@PathVariable Long id) {
        return ApiResponse.success(service.previewFinish(id));
    }

    /** 完成制作：必须带操作者；豆量全量满足才扣减并推进状态。 */
    @PostMapping("/{id}/finish")
    public ApiResponse<OrderTicketDetail> finish(@PathVariable Long id, @RequestBody(required = false) FinishTicketRequest req) {
        return ApiResponse.success(service.finish(id, req));
    }

    @PostMapping("/{id}/settle")
    public ApiResponse<OrderTicketDetail> settle(
            @PathVariable Long id,
            @RequestBody(required = false) SettleTicketRequest req) {
        return ApiResponse.success(service.settle(id, req));
    }
}
