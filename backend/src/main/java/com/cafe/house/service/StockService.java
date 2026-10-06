package com.cafe.house.service;

import com.cafe.house.common.BizException;
import com.cafe.house.entity.BeanBatch;
import com.cafe.house.entity.BeanConsumption;
import com.cafe.house.entity.MenuItem;
import com.cafe.house.entity.MenuRecipe;
import com.cafe.house.entity.OrderTicket;
import com.cafe.house.repository.BeanBatchRepository;
import com.cafe.house.repository.BeanConsumptionRepository;
import com.cafe.house.repository.MenuItemRepository;
import com.cafe.house.repository.MenuRecipeRepository;
import com.cafe.house.repository.OrderTicketRepository;
import com.cafe.house.repository.TableInfoRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存视图服务：
 * <ul>
 *   <li>点单时的库存提示：每个在售饮品能否按当前配方做一杯、缺什么；</li>
 *   <li>批次追溯：某批次被哪些账单/饮品/操作者在何时消耗了多少。</li>
 * </ul>
 * 只读，不加锁；真正的扣减决策只在完成制作事务内加锁完成。
 */
@Service
public class StockService {

    @Autowired
    private MenuItemRepository menuRepo;

    @Autowired
    private MenuRecipeRepository recipeRepo;

    @Autowired
    private BeanBatchRepository batchRepo;

    @Autowired
    private BeanConsumptionRepository consumptionRepo;

    @Autowired
    private OrderTicketRepository ticketRepo;

    @Autowired
    private TableInfoRepository tableRepo;

    /** 在售饮品的单杯库存视图（POS 菜单卡片直接消费）。 */
    @Transactional(readOnly = true)
    public List<MenuStockView> onSaleStock() {
        List<MenuItem> menus = menuRepo.findByStatus("在售");
        return buildStockViews(menus);
    }

    /** 指定饮品（含下架）的单杯库存视图，供配方维护后即时核对。 */
    @Transactional(readOnly = true)
    public MenuStockView menuStock(Long menuItemId) {
        MenuItem m = menuRepo.findById(menuItemId).orElseThrow(() -> new BizException("饮品不存在"));
        return buildStockViews(List.of(m)).get(0);
    }

    private List<MenuStockView> buildStockViews(List<MenuItem> menus) {
        if (menus.isEmpty()) {
            return List.of();
        }
        List<MenuRecipe> recipes = recipeRepo.findByMenuItemIdIn(
                menus.stream().map(m -> m.id).toList());
        Map<Long, List<MenuRecipe>> recipeMap = new LinkedHashMap<>();
        for (MenuRecipe r : recipes) {
            recipeMap.computeIfAbsent(r.menuItemId, k -> new ArrayList<>()).add(r);
        }
        // 豆种 -> 当前「已入库有余量」合计克数
        Map<String, Integer> availableByOrigin = new HashMap<>();
        for (BeanBatch b : batchRepo.findAvailable()) {
            availableByOrigin.merge(b.origin, b.remainingG, Integer::sum);
        }

        List<MenuStockView> views = new ArrayList<>();
        for (MenuItem m : menus) {
            List<MenuRecipe> lines = recipeMap.getOrDefault(m.id, List.of());
            List<BeanNeedView> needs = new ArrayList<>();
            boolean coffee = !"非咖啡".equals(m.category);
            boolean enough = true;
            String reason = null;
            if (coffee && lines.isEmpty()) {
                enough = false;
                reason = "未维护配方，暂无法制作（请管理员补配方）";
            }
            for (MenuRecipe r : lines) {
                int avail = availableByOrigin.getOrDefault(r.beanOrigin, 0);
                boolean beanEnough = avail >= r.grams;
                if (!beanEnough) {
                    enough = false;
                }
                needs.add(new BeanNeedView(r.beanOrigin, r.grams, avail, beanEnough));
            }
            if (!enough && reason == null) {
                List<String> lack = new ArrayList<>();
                for (BeanNeedView n : needs) {
                    if (!n.enough()) {
                        lack.add(n.beanOrigin() + " 缺 " + (n.requiredGrams() - n.availableGrams()) + " 克");
                    }
                }
                reason = "库存不足：" + String.join("；", lack);
            }
            views.add(new MenuStockView(m.id, m.name, m.category, coffee,
                    lines.size() > 0, enough, reason, needs));
        }
        return views;
    }

    /** 批次消耗追溯：批次汇总 + 逐条流水（账单号、桌号、饮品、克数、操作者、时间）。 */
    @Transactional(readOnly = true)
    public BatchTraceView traceBatch(Long batchId) {
        BeanBatch b = batchRepo.findById(batchId).orElseThrow(() -> new BizException("批次不存在"));
        List<BeanConsumption> rows = consumptionRepo.findByBatchIdOrderByConsumedAtAscIdAsc(batchId);

        Map<Long, OrderTicket> ticketCache = new HashMap<>();
        Map<Long, String> tableNoCache = new HashMap<>();
        List<ConsumptionTraceView> views = new ArrayList<>();
        int total = 0;
        for (BeanConsumption c : rows) {
            OrderTicket t = ticketCache.computeIfAbsent(c.ticketId,
                    id -> ticketRepo.findById(id).orElse(null));
            String tableNo = null;
            if (t != null && t.tableId != null) {
                tableNo = tableNoCache.computeIfAbsent(t.tableId, tid -> tableRepo.findById(tid)
                        .map(x -> x.tableNo).orElse(null));
            }
            views.add(new ConsumptionTraceView(c.id, c.ticketId, tableNo,
                    c.menuItemId, c.menuName, c.beanOrigin, c.grams,
                    c.operator, c.consumedAt, t == null ? null : t.status));
            total += c.grams;
        }
        return new BatchTraceView(b.id, b.batchNo, b.origin, b.roastLevel,
                b.weightKg, b.remainingG, b.status, b.storedAt,
                rows.size(), total, views);
    }

    // ---------------- 视图记录 ----------------

    public record BeanNeedView(String beanOrigin, int requiredGrams,
                               int availableGrams, boolean enough) {
    }

    public record MenuStockView(Long menuItemId, String menuName, String category,
                                boolean coffee, boolean hasRecipe, boolean enough,
                                String reason, List<BeanNeedView> needs) {
    }

    public record ConsumptionTraceView(Long consumptionId, Long ticketId, String tableNo,
                                       Long menuItemId, String menuName, String beanOrigin,
                                       int grams, String operator,
                                       java.time.LocalDateTime consumedAt, String ticketStatus) {
    }

    public record BatchTraceView(Long batchId, String batchNo, String origin, String roastLevel,
                                 java.math.BigDecimal weightKg, int remainingGrams,
                                 String status, java.time.LocalDateTime storedAt,
                                 int consumptionCount, int consumedGrams,
                                 List<ConsumptionTraceView> consumptions) {
    }
}
