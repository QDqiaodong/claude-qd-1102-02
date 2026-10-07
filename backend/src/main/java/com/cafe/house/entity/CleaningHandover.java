package com.cafe.house.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 清洁交接流水：每次结账入队、接单、暂存原因、撤回、完成均追加一条记录。
 * 该表没有更新和删除路径，后续开台或再次清洁不会改写历史。
 */
@Entity
@Table(name = "cleaning_handover")
public class CleaningHandover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "task_id", nullable = false)
    public Long taskId;

    @Column(name = "table_id", nullable = false)
    public Long tableId;

    @Column(name = "ticket_id")
    public Long ticketId;

    @Column(nullable = false, length = 32)
    public String event;

    @Column(name = "from_status", length = 16)
    public String fromStatus;

    @Column(name = "to_status", length = 16)
    public String toStatus;

    @Column(name = "operator_name", length = 32)
    public String operatorName;

    @Column(name = "expected_finish_at")
    public LocalDateTime expectedFinishAt;

    @Column(name = "unfinished_reason", length = 500)
    public String unfinishedReason;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();
}
