package com.cafe.house.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 清洁交接流水（只追加 append-only）：结账转清洁、接单、暂存原因、完成清洁、
 * 撤回清洁各写一条。记录只新增、不更新、不删除——后续再清洁或再次开台都不会
 * 改写任何历史交接记录。管理员查看「最近一次交接记录」即读本表。
 */
@Entity
@Table(name = "cleaning_log")
public class CleaningLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "task_id", nullable = false)
    public Long taskId;

    @Column(name = "table_id", nullable = false)
    public Long tableId;

    /** 桌号快照 */
    @Column(name = "table_no", nullable = false, length = 16)
    public String tableNo;

    /** 关联账单 id（可空，兼容历史遗留清洁中桌台补录的单据） */
    @Column(name = "ticket_id")
    public Long ticketId;

    /** 事件：结账转清洁 / 接单 / 暂存原因 / 完成清洁 / 撤回清洁 */
    @Column(name = "action", nullable = false, length = 16)
    public String action;

    /** 事件操作人：收银员或清洁责任人（撤回时记录是谁撤回的） */
    @Column(name = "actor", length = 32)
    public String actor;

    /** 事件发生时的清洁单状态快照：待接单 / 清洁中 / 已完成 */
    @Column(name = "task_status", nullable = false, length = 16)
    public String taskStatus;

    /** 事件备注：暂存原因内容、撤回时保留的未完成原因等 */
    @Column(name = "remark", length = 255)
    public String remark;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();
}
