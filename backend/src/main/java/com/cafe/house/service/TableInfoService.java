package com.cafe.house.service;

import com.cafe.house.common.BizException;
import com.cafe.house.common.Dict;
import com.cafe.house.entity.CleaningTask;
import com.cafe.house.entity.TableInfo;
import com.cafe.house.repository.CleaningTaskRepository;
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
    private CleaningTaskRepository cleaningTaskRepo;

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
        // 新桌台一律「空闲」：占用/清洁中只能由开台与清洁交接流程驱动，不能手工指定
        t.status = "空闲";
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
        // 桌台状态只能走业务流程（开台 / 结账转清洁 / 完成清洁），编辑表单不允许手工改状态，
        // 杜绝收银员或管理员绕过清洁直接把「清洁中」改成「空闲」。
        if (input.status != null && !input.status.equals(t.status)) {
            throw new BizException("桌台状态不能手工修改：空闲→占用由收银员开台，占用→清洁中由结账触发，清洁中→空闲由清洁人员完成清洁");
        }
        return repo.save(t);
    }

    /**
     * 开台（收银员）：只有「空闲」桌台可开台，行锁内推进为「占用」。
     *
     * <p>并发口径：对桌台行加写锁，收银员连点开台、与清洁完成并发时只接受一个合法推进。
     * 清洁中（含待接单/清洁中清洁单）一律拦截并给出原因，收银员不能绕过清洁直接为下一桌开台。</p>
     */
    @Transactional
    public TableInfo open(Long id) {
        List<TableInfo> locked = repo.findByIdForUpdate(id);
        if (locked.isEmpty()) {
            throw new BizException("桌台不存在");
        }
        TableInfo t = locked.get(0);
        if ("占用".equals(t.status)) {
            throw new BizException("桌台已开台，请勿重复点击（可刷新查看最新状态）");
        }
        if ("清洁中".equals(t.status)) {
            List<CleaningTask> active = cleaningTaskRepo.findByTableIdAndStatusIn(
                    id, Dict.CLEANING_ACTIVE_STATUS);
            String who = active.isEmpty() || active.get(0).cleaner == null
                    ? "等待清洁人员接单"
                    : "清洁责任人「" + active.get(0).cleaner + "」清洁中";
            throw new BizException("桌台清洁尚未完成（" + who
                    + "），不能直接开台；请等待清洁人员「完成清洁」、桌台显示为空闲后再开台");
        }
        t.status = "占用";
        return repo.save(t);
    }
}
