package com.cafe.house.common;

import java.util.List;

/**
 * 咖啡馆业务词典与规则参数表：咖啡豆批次状态机、烘焙度、饮品类别、
 * 桌台状态、点单出单状态流转。
 *
 * <p>规则集中在此，便于出题时被要求调整或扩展；Service 通过本类的常量与
 * 校验方法做唯一性之外的业务合法性判断。</p>
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 咖啡豆批次 ----------------
    /** 烘焙度：浅 / 中 / 深 */
    public static final List<String> ROAST_LEVELS = List.of("浅", "中", "深");

    /** 批次状态：待烘焙 → 烘焙中 → 已入库 → 已用完 */
    public static final List<String> BATCH_STATUS = List.of("待烘焙", "烘焙中", "已入库", "已用完");

    /** 批次状态机：只能逐级前进，不可跳级、不可回退 */
    public static final List<String> BATCH_FLOW = List.of("待烘焙", "烘焙中", "已入库", "已用完");

    // ---------------- 饮品菜单 ----------------
    /** 饮品类别：意式 / 手冲 / 非咖啡 */
    public static final List<String> MENU_CATEGORIES = List.of("意式", "手冲", "非咖啡");

    /** 饮品状态：在售 / 下架 */
    public static final List<String> MENU_STATUS = List.of("在售", "下架");

    // ---------------- 桌台 ----------------
    /** 桌台状态：空闲 / 占用 / 清洁中 */
    public static final List<String> TABLE_STATUS = List.of("空闲", "占用", "清洁中");

    // ---------------- 点单出单 ----------------
    /** 账单状态：制作中 → 已完成 → 已结账 */
    public static final List<String> TICKET_STATUS = List.of("制作中", "已完成", "已结账");

    /** 账单状态机：只能逐级前进（制作中→已完成→已结账） */
    public static final List<String> TICKET_FLOW = List.of("制作中", "已完成", "已结账");

    // ---------------- 清洁交接 ----------------
    /** 清洁单状态：结账后待接单，清洁人员接单后清洁中，完成后归档为已完成 */
    public static final List<String> CLEANING_TASK_STATUS = List.of("待接单", "清洁中", "已完成");

    /** 清洁交接事件：只追加，不覆盖、不修改历史 */
    public static final List<String> CLEANING_EVENTS = List.of(
            "结账待清洁", "接单", "暂存原因", "完成清洁", "撤回清洁");

    // ---------------- 取值 / 校验方法 ----------------

    public static boolean isValidRoastLevel(String s) {
        return s != null && ROAST_LEVELS.contains(s);
    }

    public static boolean isValidBatchStatus(String s) {
        return s != null && BATCH_STATUS.contains(s);
    }

    /** 批次状态只能逐级前进：待烘焙→烘焙中→已入库→已用完 */
    public static boolean canTransferBatch(String from, String to) {
        int i = BATCH_FLOW.indexOf(from);
        return i >= 0 && i + 1 < BATCH_FLOW.size() && BATCH_FLOW.get(i + 1).equals(to);
    }

    public static boolean isValidMenuCategory(String s) {
        return s != null && MENU_CATEGORIES.contains(s);
    }

    public static boolean isValidMenuStatus(String s) {
        return s != null && MENU_STATUS.contains(s);
    }

    public static boolean isValidTableStatus(String s) {
        return s != null && TABLE_STATUS.contains(s);
    }

    public static boolean isValidTicketStatus(String s) {
        return s != null && TICKET_STATUS.contains(s);
    }

    /** 账单状态只能逐级前进：制作中→已完成→已结账 */
    public static boolean canTransferTicket(String from, String to) {
        int i = TICKET_FLOW.indexOf(from);
        return i >= 0 && i + 1 < TICKET_FLOW.size() && TICKET_FLOW.get(i + 1).equals(to);
    }
}
