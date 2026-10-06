package com.cafe.house.service;

import com.cafe.house.entity.BeanBatch;
import com.cafe.house.repository.BeanBatchRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 咖啡豆需求分配器：账单完成制作时，按「当时保存的配方」把每条明细的豆量需求
 * 拆分到具体批次。
 *
 * <p>分配规则（只有这里能决定批次，前台无法插手）：</p>
 * <ul>
 *   <li>只从「已入库 且 有余量」的批次中分配；</li>
 *   <li>同一豆种（批次产地）按入库时间最早优先（FIFO，stored_at 升序），
 *       早批次用完再用下一批，单一批次不足可跨批次；</li>
 *   <li>整张账单全量满足才算成功，否则返回 shortage 明细，由调用方整体回滚。</li>
 * </ul>
 *
 * <p>{@link #planLocked} 在完成事务内调用，对批次行加 FOR UPDATE 写锁；
 * {@link #planPreview} 不加锁，仅供页面预览/点单提示。</p>
 */
@Component
public class BeanAllocator {

    @Autowired
    private BeanBatchRepository batchRepo;

    /** 一条明细对一种豆的总需求（每杯克数 × 数量）。 */
    public record Demand(Long ticketItemId, Long menuItemId, String menuName,
                         String beanOrigin, int grams) {
    }

    /** 一次拆分结果：某条明细从某批次扣多少克。同一明细跨批次时会有多行。 */
    public record Split(Long ticketItemId, Long menuItemId, String menuName,
                        Long batchId, String batchNo, String beanOrigin, int grams) {
    }

    /** 缺口信息：某豆种本单需要、可分配余量、涉及饮品。 */
    public record Shortage(String beanOrigin, int requiredGrams,
                           int availableGrams, List<String> menuNames) {
    }

    /** 分配方案：拆分行 + 缺口 + 涉及的批次（加锁版为受管实体，供调用方扣减）。 */
    public record Plan(List<Split> splits, List<Shortage> shortages, List<BeanBatch> batches) {
        public boolean satisfiable() {
            return shortages.isEmpty();
        }
    }

    /** 加锁版：完成制作专用。按主键顺序一次性锁定涉及批次（内存中再按 FIFO 分配）。 */
    public Plan planLocked(List<Demand> demands) {
        List<String> origins = distinctOrigins(demands);
        if (origins.isEmpty()) {
            return new Plan(List.of(), List.of(), List.of());
        }
        List<BeanBatch> batches = batchRepo.findAvailableForUpdateByOrigins(origins);
        return build(demands, batches);
    }

    /** 只读预览版：不加锁、不扣减，用于完成前提示与点单库存提示。 */
    public Plan planPreview(List<Demand> demands) {
        List<String> origins = distinctOrigins(demands);
        if (origins.isEmpty()) {
            return new Plan(List.of(), List.of(), List.of());
        }
        List<BeanBatch> available = batchRepo.findAvailable();
        List<BeanBatch> matched = available.stream()
                .filter(b -> origins.contains(b.origin))
                .toList();
        return build(demands, matched);
    }

    private Plan build(List<Demand> demands, List<BeanBatch> candidatesIn) {
        // 数据库按主键加锁（锁顺序全局一致）；内存里统一按 FIFO（入库先后）排序
        List<BeanBatch> candidates = new ArrayList<>(candidatesIn);
        candidates.sort(Comparator.comparing((BeanBatch b) -> b.storedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(b -> b.id));
        // 豆种 -> 可分配批次池，统一 FIFO 排序（最早入库在前）
        Map<String, List<BatchStock>> pools = new LinkedHashMap<>();
        for (BeanBatch b : candidates) {
            pools.computeIfAbsent(b.origin, k -> new ArrayList<>())
                    .add(new BatchStock(b));
        }
        pools.values().forEach(list -> list.sort(
                Comparator.comparing((BatchStock s) -> s.batch.storedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(s -> s.batch.id)));

        // 豆种维度的总需求 / 初始可分配余量 / 涉及饮品名
        Map<String, Integer> required = new LinkedHashMap<>();
        Map<String, Integer> initialAvailable = new LinkedHashMap<>();
        Map<String, List<String>> menuNames = new LinkedHashMap<>();
        for (String origin : pools.keySet()) {
            initialAvailable.put(origin, pools.get(origin).stream()
                    .mapToInt(s -> s.remaining).sum());
        }

        List<Split> splits = new ArrayList<>();
        List<Demand> ordered = new ArrayList<>(demands);
        ordered.sort(Comparator.comparing(Demand::ticketItemId));
        for (Demand d : ordered) {
            required.merge(d.beanOrigin, d.grams, Integer::sum);
            menuNames.computeIfAbsent(d.beanOrigin, k -> new ArrayList<>());
            if (!menuNames.get(d.beanOrigin).contains(d.menuName)) {
                menuNames.get(d.beanOrigin).add(d.menuName);
            }
            List<BatchStock> pool = pools.get(d.beanOrigin);
            if (pool == null) {
                continue; // 该豆种一个已入库有余量的批次都没有
            }
            int left = d.grams;
            for (BatchStock stock : pool) {
                if (left <= 0) {
                    break;
                }
                if (stock.remaining <= 0) {
                    continue;
                }
                int take = Math.min(left, stock.remaining);
                splits.add(new Split(d.ticketItemId, d.menuItemId, d.menuName,
                        stock.batch.id, stock.batch.batchNo, d.beanOrigin, take));
                stock.remaining -= take;
                left -= take;
            }
        }

        List<Shortage> shortages = new ArrayList<>();
        for (Map.Entry<String, Integer> e : required.entrySet()) {
            int avail = initialAvailable.getOrDefault(e.getKey(), 0);
            if (e.getValue() > avail) {
                shortages.add(new Shortage(e.getKey(), e.getValue(), avail,
                        menuNames.getOrDefault(e.getKey(), List.of())));
            }
        }
        return new Plan(splits, shortages, candidates);
    }

    private List<String> distinctOrigins(List<Demand> demands) {
        return demands.stream().map(Demand::beanOrigin).distinct().toList();
    }

    /** 分配过程中的可变批次余量视图。 */
    private static final class BatchStock {
        private final BeanBatch batch;
        private int remaining;

        private BatchStock(BeanBatch batch) {
            this.batch = batch;
            this.remaining = batch.remainingG;
        }
    }
}
