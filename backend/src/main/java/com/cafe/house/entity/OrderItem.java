package com.cafe.house.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 账单明细：每条记录属于某账单、指向某饮品、带数量。
 *
 * <p>menuName / unitPrice / menuCategory 为【点单时快照】，
 * 事后菜单改名、改价、改类别都不会影响历史账单金额与配方判定口径。</p>
 */
@Entity
@Table(name = "order_item")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "ticket_id", nullable = false)
    public Long ticketId;

    @Column(name = "menu_item_id", nullable = false)
    public Long menuItemId;

    @Column(name = "qty", nullable = false)
    public Integer qty;

    @Column(name = "menu_name", nullable = false, length = 64)
    public String menuName;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    public BigDecimal unitPrice;

    @Column(name = "menu_category", nullable = false, length = 16)
    public String menuCategory;
}
