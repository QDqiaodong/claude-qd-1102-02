package com.cafe.house.service;

import com.cafe.house.common.BizException;
import com.cafe.house.common.Dict;
import com.cafe.house.entity.TableInfo;
import com.cafe.house.repository.TableInfoRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TableInfoService {

    @Autowired
    private TableInfoRepository repo;

    @Autowired
    private CleaningService cleaningService;

    @Transactional(readOnly = true)
    public List<CleaningService.TableDetail> list(String status) {
        List<TableInfo> tables = status == null ? repo.findAll() : repo.findByStatus(status);
        return cleaningService.enrichTables(tables);
    }

    @Transactional
    public CleaningService.TableDetail create(TableInfo input) {
        if (input.tableNo == null || input.tableNo.isBlank()) {
            throw new BizException("桌号不能为空");
        }
        if (repo.existsByTableNo(input.tableNo)) {
            throw new BizException("桌号已存在");
        }
        if (input.seats == null || input.seats <= 0) {
            throw new BizException("座位数必须大于 0");
        }
        if (input.status != null && !"空闲".equals(input.status)) {
            throw new BizException("新增桌台必须为「空闲」；占用和清洁中只能由结账/清洁流程产生");
        }
        TableInfo t = new TableInfo();
        t.tableNo = input.tableNo;
        t.seats = input.seats;
        t.status = "空闲";
        return cleaningService.enrichTables(List.of(repo.save(t))).get(0);
    }

    /** 只允许维护桌号、座位数；状态必须走开台、结账、清洁交接流程。 */
    @Transactional
    public CleaningService.TableDetail update(Long id, TableInfo input) {
        TableInfo t = repo.findById(id).orElseThrow(() -> new BizException("桌台不存在"));
        if (input.tableNo != null) {
            if (!input.tableNo.equals(t.tableNo) && repo.existsByTableNo(input.tableNo)) {
                throw new BizException("桌号已存在");
            }
            t.tableNo = input.tableNo;
        }
        if (input.seats != null) {
            if (input.seats <= 0) {
                throw new BizException("座位数必须大于 0");
            }
            t.seats = input.seats;
        }
        if (input.status != null && !Dict.isValidTableStatus(input.status)) {
            throw new BizException("状态必须是 空闲 / 占用 / 清洁中 之一");
        }
        if (input.status != null && !input.status.equals(t.status)) {
            throw new BizException("不能手工改写桌台状态：结账后必须清洁，清洁完成后才会回到空闲");
        }
        return cleaningService.enrichTables(List.of(repo.save(t))).get(0);
    }

    /** 开台动作统一走清洁服务的行锁与清洁队列校验。 */
    @Transactional
    public CleaningService.TableDetail open(Long id, CleaningService.OpenTableRequest req) {
        TableInfo table = cleaningService.openTable(id, req);
        return cleaningService.enrichTables(List.of(table)).get(0);
    }
}
