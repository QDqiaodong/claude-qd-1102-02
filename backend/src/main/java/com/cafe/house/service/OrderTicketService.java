package com.cafe.house.service;

import com.cafe.house.common.BizException;
import com.cafe.house.common.Dict;
import com.cafe.house.entity.BeanBatch;
import com.cafe.house.entity.BeanConsumption;
import com.cafe.house.entity.MenuItem;
import com.cafe.house.entity.MenuRecipe;
import com.cafe.house.entity.OrderItem;
import com.cafe.house.entity.OrderTicket;
import com.cafe.house.entity.TableInfo;
import com.cafe.house.repository.BeanBatchRepository;
import com.cafe.house.repository.BeanConsumptionRepository;
import com.cafe.house.repository.MenuItemRepository;
import com.cafe.house.repository.MenuRecipeRepository;
import com.cafe.house.repository.OrderItemRepository;
import com.cafe.house.repository.OrderTicketRepository;
import com.cafe.house.repository.TableInfoRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrderTicketService {

    @Autowired
    private OrderTicketRepository ticketRepo;

    @Autowired
    private OrderItemRepository itemRepo;

    @Autowired
    private MenuItemRepository menuRepo;

    @Autowired
    private MenuRecipeRepository recipeRepo;

    @Autowired
    private TableInfoRepository tableRepo;

    @Autowired
    private BeanAllocator allocator;

    @Autowired
    private BeanBatchRepository batchRepo;

    @Autowired
    private BeanConsumptionRepository consumptionRepo;

    @Autowired
    private CleaningService cleaningService;

    /** 同一桌台允许的未结账状态集合 */
    private static final List<String> ACTIVE_STATUSES = List.of("制作中", "已完成");

    public List<OrderTicket> list(String status) {
        return status == null ? ticketRepo.findAll() : ticketRepo.findByStatus(status);
    }

    /** 开台点单：校验桌台已开台、无重复未结账账单，按点单时价格快照汇总 total。 */
    @Transactional
    public OrderTicketDetail create(CreateTicketRequest req) {
        if (req.tableId() == null) {
            throw new BizException("请选择桌台");
        }
        TableInfo table = tableRepo.findById(req.tableId())
                .orElseThrow(() -> new BizException("桌台不存在"));
        if (!"占用".equals(table.status)) {
            throw new BizException("桌台尚未开台，无法点单");
        }
        List<OrderTicket> active = ticketRepo.findByTableIdAndStatusIn(req.tableId(), ACTIVE_STATUSES);
        if (!active.isEmpty()) {
            throw new BizException("该桌台已有未结账账单");
        }
        OrderTicket ticket = new OrderTicket();
        ticket.tableId = req.tableId();
        ticket.status = "制作中";
        ticket.total = BigDecimal.ZERO;
        ticket = ticketRepo.save(ticket);

        List<OrderItemInput> items = req.items() == null ? List.of() : req.items();
        for (OrderItemInput in : items) {
            addItemInternal(ticket, in);
        }
        ticket.total = computeTotal(ticket.id);
        ticketRepo.save(ticket);
        return detail(ticket.id);
    }

    /** 加单：只有「制作中」账单可加；校验饮品在售、数量>0，按快照重算 total。 */
    @Transactional
    public OrderTicketDetail addItem(Long id, AddItemRequest req) {
        OrderTicket ticket = ticketRepo.findById(id).orElseThrow(() -> new BizException("账单不存在"));
        if (!"制作中".equals(ticket.status)) {
            throw new BizException("只有「制作中」的账单可以加单");
        }
        addItemInternal(ticket, new OrderItemInput(req.menuItemId(), req.qty()));
        ticket.total = computeTotal(id);
        ticketRepo.save(ticket);
        return detail(id);
    }

    /**
     * 标记完成（带操作者）：制作中 → 已完成，并在【同一事务】内
     * 按当时保存的配方做豆量分配与扣减、写消耗流水。
     *
     * <p>并发口径：先 SELECT ... FOR UPDATE 锁账单行——重复提交时后到事务
     * 等锁后看到状态已是「已完成」直接拒绝；再按全局一致顺序锁涉及批次，
     * 余量不足则抛出异常整体回滚（账单保持制作中、任何批次一克不扣）。</p>
     */
    @Transactional
    public OrderTicketDetail finish(Long id, FinishTicketRequest req) {
        String operator = req == null || req.operator() == null ? null : req.operator().trim();
        if (operator == null || operator.isEmpty()) {
            throw new BizException("请选择或填写完成操作者（收银员）");
        }
        if (operator.length() > 32) {
            throw new BizException("操作者姓名不能超过 32 个字符");
        }

        OrderTicket ticket = lockTicket(id);
        if ("已完成".equals(ticket.status) || "已结账".equals(ticket.status)) {
            // 并发重复提交：后到事务拿锁后看到的已是终态，直接拒绝，状态不再推进
            throw new BizException("该账单已完成制作，请勿重复提交（豆量已扣减，账单状态只推进一次）");
        }
        if (!"制作中".equals(ticket.status)) {
            throw new BizException("只有「制作中」的账单可以标记完成");
        }

        List<OrderItem> items = itemRepo.findByTicketId(id);
        List<MenuRecipe> recipes = recipeRepo.findByMenuItemIdIn(
                items.stream().map(i -> i.menuItemId).distinct().toList());
        List<BeanAllocator.Demand> demands = buildDemands(items, recipes);

        BeanAllocator.Plan plan = allocator.planLocked(demands);
        if (!plan.satisfiable()) {
            throw new BizException(buildShortageMessage(plan.shortages()));
        }

        LocalDateTime now = LocalDateTime.now();
        applyDeduction(ticket, plan, operator, now);

        ticket.status = "已完成";
        ticket.finishedBy = operator;
        ticket.finishedAt = now;
        ticketRepo.save(ticket);
        return detail(id);
    }

    /**
     * 结账：已完成 → 已结账。结账【不再直接释放为空闲】：同一事务内
     * 账单行锁 → 桌台行锁串行，桌台「占用 → 清洁中」并生成一张「待接单」
     * 清洁交接单（写结账收银员与「结账转清洁」流水）。只有清洁人员完成清洁后，
     * 桌台才回到「空闲」可开台。
     *
     * <p>并发口径：先锁账单行，重复结账的后到事务看到终态直接拒绝；
     * 再由 CleaningService 锁桌台行并入清洁队列，与开台/清洁动作互不覆盖。</p>
     */
    @Transactional
    public OrderTicketDetail settle(Long id, SettleTicketRequest req) {
        String cashier = req == null || req.operator() == null ? null : req.operator().trim();
        if (cashier == null || cashier.isEmpty()) {
            throw new BizException("请选择或填写结账收银员");
        }
        if (cashier.length() > 32) {
            throw new BizException("收银员姓名不能超过 32 个字符");
        }

        OrderTicket ticket = lockTicket(id);
        if ("已结账".equals(ticket.status)) {
            throw new BizException("该账单已结账，请勿重复提交（桌台已进入清洁队列）");
        }
        if (!Dict.canTransferTicket(ticket.status, "已结账")) {
            throw new BizException("请先完成制作再结账");
        }

        LocalDateTime now = LocalDateTime.now();
        ticket.status = "已结账";
        ticket.settledBy = cashier;
        ticket.settledAt = now;
        ticketRepo.save(ticket);

        if (ticket.tableId != null) {
            // 同事务入清洁队列：桌台置清洁中、建待接单清洁单、写交接流水。
            // 任一步失败则整单回滚（账单保持已完成、桌台保持占用）。
            TableInfo table = tableRepo.findById(ticket.tableId)
                    .orElseThrow(() -> new BizException("桌台不存在"));
            cleaningService.enqueueFromSettle(table, ticket, cashier, now);
        }
        return detail(id);
    }

    /**
     * 完成前预览（只读，不锁、不扣）：给收银员展示本次将消耗哪些批次/克数，
     * 或为什么做不了（缺配方、哪些豆种差多少克）。与完成动作同一套分配算法，
     * 真正扣减时仍以加锁后的实时余量为准。
     */
    @Transactional(readOnly = true)
    public FinishPreview previewFinish(Long id) {
        OrderTicket ticket = ticketRepo.findById(id).orElseThrow(() -> new BizException("账单不存在"));
        List<OrderItem> items = itemRepo.findByTicketId(id);
        List<MenuRecipe> recipes = recipeRepo.findByMenuItemIdIn(
                items.stream().map(i -> i.menuItemId).distinct().toList());

        List<MissingRecipeLine> missingRecipes = new ArrayList<>();
        List<BeanAllocator.Demand> demands = new ArrayList<>();
        Map<Long, List<MenuRecipe>> recipeMap = new LinkedHashMap<>();
        for (MenuRecipe r : recipes) {
            recipeMap.computeIfAbsent(r.menuItemId, k -> new ArrayList<>()).add(r);
        }
        for (OrderItem it : items) {
            if ("非咖啡".equals(it.menuCategory)) {
                continue;
            }
            List<MenuRecipe> lines = recipeMap.get(it.menuItemId);
            if (lines == null || lines.isEmpty()) {
                missingRecipes.add(new MissingRecipeLine(it.id, it.menuItemId, it.menuName, it.qty));
                continue;
            }
            for (MenuRecipe r : lines) {
                demands.add(new BeanAllocator.Demand(it.id, it.menuItemId, it.menuName,
                        r.beanOrigin, r.grams * it.qty));
            }
        }

        List<BatchPreviewLine> batchLines = new ArrayList<>();
        List<ShortageView> shortageViews = List.of();
        boolean satisfiable = missingRecipes.isEmpty();
        if (!demands.isEmpty()) {
            BeanAllocator.Plan plan = allocator.planPreview(demands);
            satisfiable = satisfiable && plan.satisfiable();
            Map<Long, BeanBatch> batchMap = new LinkedHashMap<>();
            for (BeanBatch b : plan.batches()) {
                batchMap.put(b.id, b);
            }
            // 按批次汇总展示（同时保留 FIFO 明细，跨批次时一目了然）
            Map<Long, int[]> gramsByBatch = new LinkedHashMap<>(); // [grams]
            Map<Long, List<SplitPreview>> splitMap = new LinkedHashMap<>();
            for (BeanAllocator.Split s : plan.splits()) {
                gramsByBatch.merge(s.batchId(), new int[]{s.grams()}, (a, b2) -> {
                    a[0] += b2[0];
                    return a;
                });
                splitMap.computeIfAbsent(s.batchId(), k -> new ArrayList<>())
                        .add(new SplitPreview(s.ticketItemId(), s.menuName(),
                                s.beanOrigin(), s.grams()));
            }
            for (Map.Entry<Long, int[]> e : gramsByBatch.entrySet()) {
                BeanBatch b = batchMap.get(e.getKey());
                List<SplitPreview> splits = splitMap.get(e.getKey());
                batchLines.add(new BatchPreviewLine(b.id, b.batchNo, b.origin,
                        b.roastLevel, e.getValue()[0], b.remainingG, b.storedAt, splits));
            }
            shortageViews = plan.shortages().stream()
                    .map(sh -> new ShortageView(sh.beanOrigin(), sh.requiredGrams(),
                            sh.availableGrams(), sh.requiredGrams() - sh.availableGrams(),
                            sh.menuNames()))
                    .toList();
        }
        return new FinishPreview(ticket.id, ticket.status, satisfiable,
                missingRecipes, batchLines, shortageViews);
    }

    public OrderTicketDetail detail(Long id) {
        OrderTicket ticket = ticketRepo.findById(id).orElseThrow(() -> new BizException("账单不存在"));
        List<OrderItem> items = itemRepo.findByTicketId(id);
        List<OrderItemDetail> details = new ArrayList<>();
        for (OrderItem it : items) {
            // 金额一律取点单快照：菜单事后改价不影响历史账单
            BigDecimal subtotal = it.unitPrice.multiply(BigDecimal.valueOf(it.qty));
            details.add(new OrderItemDetail(it.id, it.menuItemId, it.menuName,
                    it.unitPrice, it.menuCategory, it.qty, subtotal));
        }
        List<ConsumptionView> consumptions = consumptionRepo.findByTicketIdOrderByIdAsc(id)
                .stream()
                .map(c -> new ConsumptionView(c.id, c.ticketItemId, c.batchId, c.batchNo,
                        c.beanOrigin, c.menuName, c.grams, c.operator, c.consumedAt))
                .toList();
        return new OrderTicketDetail(ticket, details, consumptions);
    }

    // ---------------- 内部方法 ----------------

    private OrderTicket lockTicket(Long id) {
        List<OrderTicket> locked = ticketRepo.findByIdForUpdate(id);
        if (locked.isEmpty()) {
            throw new BizException("账单不存在");
        }
        return locked.get(0);
    }

    private void addItemInternal(OrderTicket ticket, OrderItemInput in) {
        if (in.menuItemId() == null) {
            throw new BizException("请选择饮品");
        }
        if (in.qty() == null || in.qty() <= 0) {
            throw new BizException("数量必须大于 0");
        }
        MenuItem mi = menuRepo.findById(in.menuItemId())
                .orElseThrow(() -> new BizException("饮品不存在"));
        if (!"在售".equals(mi.status)) {
            throw new BizException("饮品「" + mi.name + "」已下架，无法下单");
        }
        // 点单时即冻结名称/单价/类别，完成制作与历史展示都不再回查菜单
        OrderItem oi = new OrderItem();
        oi.ticketId = ticket.id;
        oi.menuItemId = in.menuItemId();
        oi.qty = in.qty();
        oi.menuName = mi.name;
        oi.unitPrice = mi.price;
        oi.menuCategory = mi.category;
        itemRepo.save(oi);
    }

    /**
     * 依据【当前保存的配方】汇总豆量需求。咖啡类明细缺配方直接拒绝
     * （非咖啡类不耗豆）——这是「完成制作」时点的配方口径。
     */
    private List<BeanAllocator.Demand> buildDemands(List<OrderItem> items, List<MenuRecipe> recipes) {
        Map<Long, List<MenuRecipe>> recipeMap = new LinkedHashMap<>();
        for (MenuRecipe r : recipes) {
            recipeMap.computeIfAbsent(r.menuItemId, k -> new ArrayList<>()).add(r);
        }
        List<BeanAllocator.Demand> demands = new ArrayList<>();
        List<String> noRecipe = new ArrayList<>();
        for (OrderItem it : items) {
            if ("非咖啡".equals(it.menuCategory)) {
                continue;
            }
            List<MenuRecipe> lines = recipeMap.get(it.menuItemId);
            if (lines == null || lines.isEmpty()) {
                noRecipe.add("「" + it.menuName + "」×" + it.qty);
                continue;
            }
            for (MenuRecipe r : lines) {
                demands.add(new BeanAllocator.Demand(it.id, it.menuItemId, it.menuName,
                        r.beanOrigin, r.grams * it.qty));
            }
        }
        if (!noRecipe.isEmpty()) {
            throw new BizException("以下饮品尚未维护配方，无法完成制作："
                    + String.join("、", noRecipe) + "；请管理员先在饮品菜单中补全配方");
        }
        return demands;
    }

    /** 执行扣减：批次余量减克、余量为 0 自动置「已用完」、逐条写消耗流水。 */
    private void applyDeduction(OrderTicket ticket, BeanAllocator.Plan plan,
                                String operator, LocalDateTime now) {
        Map<Long, BeanBatch> batchMap = new LinkedHashMap<>();
        for (BeanBatch b : plan.batches()) {
            batchMap.put(b.id, b);
        }
        // 同一批次可能有多条拆分行，先汇总扣减量，避免中间态被误判为 0/非 0
        Map<Long, Integer> deductByBatch = new LinkedHashMap<>();
        for (BeanAllocator.Split s : plan.splits()) {
            deductByBatch.merge(s.batchId(), s.grams(), Integer::sum);
            BeanConsumption c = new BeanConsumption();
            c.ticketId = ticket.id;
            c.ticketItemId = s.ticketItemId();
            c.menuItemId = s.menuItemId();
            c.menuName = s.menuName();
            c.beanOrigin = s.beanOrigin();
            c.batchId = s.batchId();
            c.batchNo = s.batchNo();
            c.grams = s.grams();
            c.operator = operator;
            c.consumedAt = now;
            consumptionRepo.save(c);
        }
        for (Map.Entry<Long, Integer> e : deductByBatch.entrySet()) {
            BeanBatch b = batchMap.get(e.getKey());
            int after = b.remainingG - e.getValue();
            if (after < 0) {
                // 理论上不会发生（行锁内 FIFO 已校验），兜底防超扣
                throw new BizException("批次「" + b.batchNo + "」余量不足，本次完成已取消");
            }
            b.remainingG = after;
            if (after == 0) {
                b.status = "已用完";
            }
            batchRepo.save(b);
        }
    }

    private String buildShortageMessage(List<BeanAllocator.Shortage> shortages) {
        List<String> parts = new ArrayList<>();
        for (BeanAllocator.Shortage sh : shortages) {
            parts.add("豆种「" + sh.beanOrigin() + "」本单需 " + sh.requiredGrams()
                    + " 克，可用仅 " + sh.availableGrams() + " 克（差 "
                    + (sh.requiredGrams() - sh.availableGrams()) + " 克，涉及："
                    + String.join("、", sh.menuNames()) + "）");
        }
        return "咖啡豆库存不足以整单满足，未扣减任何批次、账单保持「制作中」：\n"
                + String.join("；\n", parts);
    }

    private BigDecimal computeTotal(Long ticketId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderItem it : itemRepo.findByTicketId(ticketId)) {
            sum = sum.add(it.unitPrice.multiply(BigDecimal.valueOf(it.qty)));
        }
        return sum;
    }

    // ---------------- 请求 / 响应记录 ----------------

    public record OrderItemInput(Long menuItemId, Integer qty) {
    }

    public record CreateTicketRequest(Long tableId, List<OrderItemInput> items) {
    }

    public record AddItemRequest(Long menuItemId, Integer qty) {
    }

    public record FinishTicketRequest(String operator) {
    }

    public record SettleTicketRequest(String operator) {
    }

    public record OrderItemDetail(
            Long id, Long menuItemId, String menuName,
            BigDecimal unitPrice, String menuCategory,
            Integer qty, BigDecimal subtotal) {
    }

    public record ConsumptionView(
            Long id, Long ticketItemId, Long batchId, String batchNo,
            String beanOrigin, String menuName, Integer grams,
            String operator, LocalDateTime consumedAt) {
    }

    public record OrderTicketDetail(OrderTicket ticket, List<OrderItemDetail> items,
                                    List<ConsumptionView> consumptions) {
    }

    public record MissingRecipeLine(Long ticketItemId, Long menuItemId,
                                    String menuName, Integer qty) {
    }

    public record SplitPreview(Long ticketItemId, String menuName,
                               String beanOrigin, Integer grams) {
    }

    public record BatchPreviewLine(Long batchId, String batchNo, String beanOrigin,
                                   String roastLevel, Integer useGrams,
                                   Integer remainingGrams, LocalDateTime storedAt,
                                   List<SplitPreview> splits) {
    }

    public record ShortageView(String beanOrigin, Integer requiredGrams,
                               Integer availableGrams, Integer lackGrams,
                               List<String> menuNames) {
    }

    public record FinishPreview(Long ticketId, String ticketStatus, boolean satisfiable,
                                List<MissingRecipeLine> missingRecipes,
                                List<BatchPreviewLine> batches,
                                List<ShortageView> shortages) {
    }
}
