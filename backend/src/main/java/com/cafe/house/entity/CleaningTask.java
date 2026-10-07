package com.cafe.house.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 桌台清洁单：账单结账时入队，清洁人员接单后记录责任人和预计完成时间。
 *
 * <p>清洁中的未完成原因可以反复暂存；完成后状态归档为「已完成」，
 * 记录不再修改。同一桌台只允许有一张「待接单 / 清洁中」清洁单。</p>
 */
@Entity
@Table(name = "cleaning_task")
public class CleaningTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "table_id", nullable = false)
    public Long tableId;

    @Column(name = "ticket_id")
    public Long ticketId;

    @Column(nullable = false, length = 16)
    public String status;

    @Column(name = "released_by", length = 32)
    public String releasedBy;

    @Column(name = "released_at", nullable = false)
    public LocalDateTime releasedAt;

    @Column(name = "cleaner_name", length = 32)
    public String cleanerName;

    @Column(name = "accepted_at")
    public LocalDateTime acceptedAt;

    @Column(name = "expected_finish_at")
    public LocalDateTime expectedFinishAt;

    @Column(name = "unfinished_reason", length = 500)
    public String unfinishedReason;

    @Column(name = "reason_updated_at")
    public LocalDateTime reasonUpdatedAt;

    @Column(name = "completed_at")
    public LocalDateTime completedAt;
}
