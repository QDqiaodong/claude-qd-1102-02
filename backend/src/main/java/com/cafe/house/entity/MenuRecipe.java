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
 * 饮品配方：某饮品每杯需要某种豆（{@code beanOrigin} 对应批次产地）多少克。
 * 一种饮品可由多个豆种组成；同一饮品同一豆种只有一行（uk_recipe_bean）。
 *
 * <p>配方是完成制作时计算豆量的唯一依据：前台不能指定批次，
 * 管理员事后调整配方不会重算已完成账单（消耗以流水快照为准）。</p>
 */
@Entity
@Table(name = "menu_recipe", uniqueConstraints = {
        @UniqueConstraint(name = "uk_recipe_bean", columnNames = {"menu_item_id", "bean_origin"})
})
public class MenuRecipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "menu_item_id", nullable = false)
    public Long menuItemId;

    /** 所需豆种：必须与「已入库」批次的 origin 一致才可能被分配。 */
    @Column(name = "bean_origin", nullable = false, length = 64)
    public String beanOrigin;

    /** 每杯用量（克）。 */
    @Column(name = "grams", nullable = false)
    public Integer grams;

    @Column(name = "created_at")
    public LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    public LocalDateTime updatedAt = LocalDateTime.now();
}
