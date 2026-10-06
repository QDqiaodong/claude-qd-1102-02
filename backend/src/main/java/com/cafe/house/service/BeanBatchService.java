package com.cafe.house.service;

import com.cafe.house.common.BizException;
import com.cafe.house.common.Dict;
import com.cafe.house.entity.BeanBatch;
import com.cafe.house.repository.BeanBatchRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class BeanBatchService {

    @Autowired
    private BeanBatchRepository repo;

    public List<BeanBatch> list(String status) {
        return status == null ? repo.findAll() : repo.findByStatus(status);
    }

    public BeanBatch create(BeanBatch input) {
        if (input.batchNo == null || input.batchNo.isBlank()) {
            throw new BizException("批次号不能为空");
        }
        if (repo.existsByBatchNo(input.batchNo.trim())) {
            throw new BizException("批次号已存在");
        }
        if (input.origin == null || input.origin.isBlank()) {
            throw new BizException("产地不能为空");
        }
        if (!Dict.isValidRoastLevel(input.roastLevel)) {
            throw new BizException("烘焙度必须是 浅 / 中 / 深 之一");
        }
        validateWeight(input.weightKg);
        BeanBatch b = new BeanBatch();
        b.batchNo = input.batchNo.trim();
        b.origin = input.origin.trim();
        b.roastLevel = input.roastLevel;
        b.weightKg = input.weightKg;
        b.remainingG = 0; // 未入库不可分配
        b.status = "待烘焙";
        return repo.save(b);
    }

    /**
     * 编辑：仅在「待烘焙 / 烘焙中」允许，且只改批次基本信息。
     * 一旦入库（豆种产地、重量已成为配方匹配与余量基准）即锁定，
     * 防止通过编辑篡改历史扣减口径或凭空改变余量。
     */
    public BeanBatch update(Long id, BeanBatch input) {
        BeanBatch b = repo.findById(id).orElseThrow(() -> new BizException("批次不存在"));
        if ("已入库".equals(b.status) || "已用完".equals(b.status)) {
            throw new BizException("批次「" + b.batchNo + "」已入库，豆种/重量等信息已锁定，不能再编辑");
        }
        if (input.batchNo != null) {
            String batchNo = input.batchNo.trim();
            if (batchNo.isBlank()) {
                throw new BizException("批次号不能为空");
            }
            if (!batchNo.equals(b.batchNo) && repo.existsByBatchNo(batchNo)) {
                throw new BizException("批次号已存在");
            }
            b.batchNo = batchNo;
        }
        if (input.origin != null) {
            if (input.origin.isBlank()) {
                throw new BizException("产地不能为空");
            }
            b.origin = input.origin.trim();
        }
        if (input.roastLevel != null) {
            if (!Dict.isValidRoastLevel(input.roastLevel)) {
                throw new BizException("烘焙度必须是 浅 / 中 / 深 之一");
            }
            b.roastLevel = input.roastLevel;
        }
        if (input.weightKg != null) {
            validateWeight(input.weightKg);
            b.weightKg = input.weightKg;
        }
        return repo.save(b);
    }

    public BeanBatch roast(Long id) {
        BeanBatch b = repo.findById(id).orElseThrow(() -> new BizException("批次不存在"));
        if (!Dict.canTransferBatch(b.status, "烘焙中")) {
            throw new BizException("只能将「待烘焙」的批次送入烘焙");
        }
        b.status = "烘焙中";
        return repo.save(b);
    }

    /** 标记入库：状态推进同时写入入库时间，并按重量初始化批次余量（克）。 */
    public BeanBatch finishRoast(Long id) {
        BeanBatch b = repo.findById(id).orElseThrow(() -> new BizException("批次不存在"));
        if (!Dict.canTransferBatch(b.status, "已入库")) {
            throw new BizException("只能将「烘焙中」的批次标记为已入库");
        }
        b.status = "已入库";
        b.remainingG = kgToGrams(b.weightKg);
        b.storedAt = LocalDateTime.now();
        return repo.save(b);
    }

    /**
     * 人工标记用完：仅「已入库」可操作。若仍有余量，余量清零（报损/封存场景，
     * 不产生账单消耗流水；流水只在完成制作时生成）。
     */
    public BeanBatch deplete(Long id) {
        BeanBatch b = repo.findById(id).orElseThrow(() -> new BizException("批次不存在"));
        if (!Dict.canTransferBatch(b.status, "已用完")) {
            throw new BizException("只有「已入库」的批次才能标记已用完");
        }
        b.status = "已用完";
        b.remainingG = 0;
        return repo.save(b);
    }

    private void validateWeight(BigDecimal weight) {
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("重量必须大于 0");
        }
    }

    /** kg(2 位小数) -> 克：1 kg = 1000 g。 */
    static int kgToGrams(BigDecimal weightKg) {
        return weightKg.setScale(2, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(1000))
                .intValueExact();
    }
}
