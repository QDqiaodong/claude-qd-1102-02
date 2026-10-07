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
 * 点单出单（账单）：引用桌台；total 按点单快照汇总；状态（制作中/已完成/已结账）。
 *
 * <p>finishedBy / finishedAt 在「完成制作」成功扣减豆量的同一事务中写入，
 * 与 bean_consumption 流水同生共死：账单状态只能推进一次。</p>
 */
@Entity
@Table(name = "order_ticket")
public class OrderTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "table_id")
    public Long tableId;

    @Column(name = "total", nullable = false, precision = 10, scale = 2)
    public BigDecimal total;

    @Column(name = "status", nullable = false, length = 16)
    public String status;

    /** 完成操作者（收银员），完成制作时必录。 */
    @Column(name = "finished_by", length = 32)
    public String finishedBy;

    /** 完成制作（豆量扣减）时间。 */
    @Column(name = "finished_at")
    public LocalDateTime finishedAt;

    /** 结账操作者（收银员）。 */
    @Column(name = "settled_by", length = 32)
    public String settledBy;

    /** 结账时间；同时也是桌台进入清洁队列的交接时间。 */
    @Column(name = "settled_at")
    public LocalDateTime settledAt;

    @Column(name = "created_at")
    public LocalDateTime createdAt = LocalDateTime.now();
}
