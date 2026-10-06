package com.cafe.house.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 咖啡豆消耗流水：只在账单「完成制作」且豆量全量满足时写入，只增不改。
 *
 * <p>同一账单明细跨批次扣减时产生多行（FIFO 拆分）；
 * (ticket_id, ticket_item_id, batch_id) 唯一约束作为「流水不重复」的数据库兜底；
 * 批次号/豆种/菜名均为快照，批次或菜单之后变动不影响历史追溯。</p>
 */
@Entity
@Table(name = "bean_consumption", uniqueConstraints = {
        @UniqueConstraint(name = "uk_consume_item_batch",
                columnNames = {"ticket_id", "ticket_item_id", "batch_id"})
})
public class BeanConsumption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "ticket_id", nullable = false)
    public Long ticketId;

    @Column(name = "ticket_item_id", nullable = false)
    public Long ticketItemId;

    @Column(name = "menu_item_id", nullable = false)
    public Long menuItemId;

    @Column(name = "menu_name", nullable = false, length = 64)
    public String menuName;

    @Column(name = "bean_origin", nullable = false, length = 64)
    public String beanOrigin;

    @Column(name = "batch_id", nullable = false)
    public Long batchId;

    @Column(name = "batch_no", nullable = false, length = 32)
    public String batchNo;

    @Column(name = "grams", nullable = false)
    public Integer grams;

    @Column(name = "operator", nullable = false, length = 32)
    public String operator;

    @Column(name = "consumed_at", nullable = false)
    public LocalDateTime consumedAt;
}
