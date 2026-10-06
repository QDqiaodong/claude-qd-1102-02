package com.cafe.house.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 桌台：桌号唯一；座位数>0；状态（空闲/占用/清洁中）。
 */
@Entity
@Table(name = "table_info")
public class TableInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "table_no", nullable = false, unique = true, length = 16)
    public String tableNo;

    @Column(name = "seats", nullable = false)
    public Integer seats;

    @Column(name = "status", nullable = false, length = 16)
    public String status;

    @Column(name = "created_at")
    public LocalDateTime createdAt = LocalDateTime.now();
}
