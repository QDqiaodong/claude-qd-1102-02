package com.cafe.house.controller;

import com.cafe.house.common.ApiResponse;
import com.cafe.house.service.StockService;
import com.cafe.house.service.StockService.BatchTraceView;
import com.cafe.house.service.StockService.MenuStockView;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 库存只读视图：POS 点单提示、配方核对、批次消耗追溯。
 * 任何写扣减都不经过这里（扣减只发生在账单完成制作事务内）。
 */
@RestController
@RequestMapping("/api/stock")
public class StockController {

    @Autowired
    private StockService service;

    /** 全部在售饮品的单杯库存情况。 */
    @GetMapping("/on-sale")
    public ApiResponse<List<MenuStockView>> onSale() {
        return ApiResponse.success(service.onSaleStock());
    }

    /** 单个饮品的库存情况（管理员保存配方后即时核对）。 */
    @GetMapping("/menu-items/{id}")
    public ApiResponse<MenuStockView> menuStock(@PathVariable Long id) {
        return ApiResponse.success(service.menuStock(id));
    }

    /** 某批次被哪些账单消耗（追溯）。 */
    @GetMapping("/bean-batches/{id}/trace")
    public ApiResponse<BatchTraceView> traceBatch(@PathVariable Long id) {
        return ApiResponse.success(service.traceBatch(id));
    }
}
