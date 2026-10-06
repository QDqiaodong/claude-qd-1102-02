package com.cafe.house.service;

import com.cafe.house.common.BizException;
import com.cafe.house.common.Dict;
import com.cafe.house.entity.TableInfo;
import com.cafe.house.repository.TableInfoRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TableInfoService {

    @Autowired
    private TableInfoRepository repo;

    public List<TableInfo> list(String status) {
        return status == null ? repo.findAll() : repo.findByStatus(status);
    }

    public TableInfo create(TableInfo input) {
        if (input.tableNo == null || input.tableNo.isBlank()) {
            throw new BizException("桌号不能为空");
        }
        if (repo.existsByTableNo(input.tableNo)) {
            throw new BizException("桌号已存在");
        }
        if (input.seats == null || input.seats <= 0) {
            throw new BizException("座位数必须大于 0");
        }
        TableInfo t = new TableInfo();
        t.tableNo = input.tableNo;
        t.seats = input.seats;
        t.status = input.status == null ? "空闲" : input.status;
        if (!Dict.isValidTableStatus(t.status)) {
            throw new BizException("状态必须是 空闲 / 占用 / 清洁中 之一");
        }
        return repo.save(t);
    }

    public TableInfo update(Long id, TableInfo input) {
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
        if (input.status != null) {
            if (!Dict.isValidTableStatus(input.status)) {
                throw new BizException("状态必须是 空闲 / 占用 / 清洁中 之一");
            }
            t.status = input.status;
        }
        return repo.save(t);
    }

    /** 开台：只有「空闲」可开台，置为「占用」；清洁中不能开台。 */
    public TableInfo open(Long id) {
        TableInfo t = repo.findById(id).orElseThrow(() -> new BizException("桌台不存在"));
        if (!"空闲".equals(t.status)) {
            throw new BizException("只有「空闲」的桌台可以开台（清洁中无法开台）");
        }
        t.status = "占用";
        return repo.save(t);
    }

    /**
     * 清洁：占用 → 清洁中（开始收台清洁）；清洁中 → 空闲（清洁完成）。
     * 空闲桌台无需清洁。
     */
    public TableInfo clean(Long id) {
        TableInfo t = repo.findById(id).orElseThrow(() -> new BizException("桌台不存在"));
        if ("占用".equals(t.status)) {
            t.status = "清洁中";
        } else if ("清洁中".equals(t.status)) {
            t.status = "空闲";
        } else {
            throw new BizException("桌台当前为空闲，无需清洁");
        }
        return repo.save(t);
    }
}
