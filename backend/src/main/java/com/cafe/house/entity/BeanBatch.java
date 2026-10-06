package com.cafe.house.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 咖啡豆批次：烘焙状态机（待烘焙→烘焙中→已入库→已用完）。
 *
 * <p>入库时按重量初始化余量 remaining_g；制作完成时由消耗流水扣减，
 * 余量归零自动置为「已用完」。批次只在「已入库 且 有余量」时可被分配。</p>
 */
@Entity
@Table(name = "bean_batch")
public class BeanBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "batch_no", nullable = false, unique = true, length = 32)
    public String batchNo;

    @Column(name = "origin", nullable = false, length = 64)
    public String origin;

    @Column(name = "roast_level", nullable = false, length = 8)
    public String roastLevel;

    @Column(name = "weight_kg", nullable = false, precision = 10, scale = 2)
    public BigDecimal weightKg;

    /** 批次余量（克）。已入库 = 可分配库存，0 表示已用尽。 */
    @Column(name = "remaining_g", nullable = false)
    public Integer remainingG = 0;

    @Column(name = "status", nullable = false, length = 16)
    public String status;

    /** 入库时间：配方扣豆时 FIFO（最早入库先分配）的排序依据。 */
    @Column(name = "stored_at")
    public LocalDateTime storedAt;

    @Column(name = "created_at")
    public LocalDateTime createdAt = LocalDateTime.now();
}
