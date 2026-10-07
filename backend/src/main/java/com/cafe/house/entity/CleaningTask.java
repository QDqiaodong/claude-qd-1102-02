package com.cafe.house.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 清洁交接单：一张已结账账单对应一次桌台清洁，结账成功即生成「待接单」清洁单，
 * 桌台同步进入「清洁中」。
 *
 * <p>状态机：待接单 →（清洁人员接单）→ 清洁中 →（完成清洁）→ 已完成（桌台回空闲）；
 * 清洁中可「撤回清洁」退回待接单（责任人清空，未完成原因保留）。
 * 每次结账都新建清洁单，已完成的单据不再被后续清洁/开台修改，历史交接以本表 +
 * cleaning_log 为准。</p>
 *
 * <p>并发口径：所有状态推进先对本行 SELECT ... FOR UPDATE，完成/开台再锁桌台行，
 * 并发点击完成清洁、撤回清洁、开台时只有一个合法推进能提交；失败的请求在事务内
 * 直接抛错回滚，不会覆盖最新责任人和暂存原因。</p>
 */
@Entity
@Table(name = "cleaning_task")
public class CleaningTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "table_id", nullable = false)
    public Long tableId;

    /** 桌号快照：桌台事后改名/删除不影响历史交接展示 */
    @Column(name = "table_no", nullable = false, length = 16)
    public String tableNo;

    /** 触发本次清洁的已结账账单 id */
    @Column(name = "ticket_id")
    public Long ticketId;

    /** 交接账单的结账收银员（快照） */
    @Column(name = "cashier", length = 32)
    public String cashier;

    /** 待接单 / 清洁中 / 已完成 */
    @Column(name = "status", nullable = false, length = 16)
    public String status;

    /** 清洁责任人：接单时记录；撤回后清空，回待接单队列等其他人接 */
    @Column(name = "cleaner", length = 32)
    public String cleaner;

    /** 结账转清洁（入队）时间 */
    @Column(name = "queued_at", nullable = false)
    public LocalDateTime queuedAt;

    /** 接单（开始清洁）时间 */
    @Column(name = "started_at")
    public LocalDateTime startedAt;

    /** 接单时填写的预计完成时间 */
    @Column(name = "eta_at")
    public LocalDateTime etaAt;

    /** 实际完成清洁时间 */
    @Column(name = "finished_at")
    public LocalDateTime finishedAt;

    /**
     * 未完成原因（暂存）：清洁过程中可多次保存，刷新页面后仍保留；
     * 撤回清洁时不清空（异常中断后下一位责任人能看到原因继续处理）。
     */
    @Column(name = "pending_reason", length = 255)
    public String pendingReason;

    /** 最近一次暂存原因的时间 */
    @Column(name = "reason_saved_at")
    public LocalDateTime reasonSavedAt;

    @Column(name = "created_at")
    public LocalDateTime createdAt = LocalDateTime.now();
}
