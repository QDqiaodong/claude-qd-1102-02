package com.cafe.house.service;

import com.cafe.house.common.BizException;
import com.cafe.house.common.Dict;
import com.cafe.house.entity.CleaningLog;
import com.cafe.house.entity.CleaningTask;
import com.cafe.house.entity.OrderTicket;
import com.cafe.house.entity.TableInfo;
import com.cafe.house.repository.CleaningLogRepository;
import com.cafe.house.repository.CleaningTaskRepository;
import com.cafe.house.repository.OrderTicketRepository;
import com.cafe.house.repository.TableInfoRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 桌台清洁交接服务。
 *
 * <p>状态主线：结账（OrderTicketService.settle，同事务）→ enqueue：桌台「占用→清洁中」、
 * 生成「待接单」清洁单与「结账转清洁」流水；清洁人员接单后记录责任人/开始时间/预计完成时间；
 * 清洁中可多次暂存未完成原因（刷新不丢）；完成清洁后桌台才回「空闲」可开台；
 * 清洁中可撤回为待接单（责任人清空、原因保留）。</p>
 *
 * <p>并发口径：所有推进先锁清洁单行（FOR UPDATE），涉及桌台可见状态的再锁桌台行。
 * 完成清洁 / 撤回清洁 / 开台并发时由库层串行，只允许一个合法推进提交；失败方在
 * 事务内抛错回滚，最新责任人、时间、暂存原因不会被失败操作覆盖。cleaning_log
 * 只追加，历史交接不被后续任何动作改写。</p>
 */
@Service
public class CleaningService {

    @Autowired
    private CleaningTaskRepository taskRepo;

    @Autowired
    private CleaningLogRepository logRepo;

    @Autowired
    private TableInfoRepository tableRepo;

    @Autowired
    private OrderTicketRepository ticketRepo;

    /** 同一桌台的未结账账单（说明桌台已被下一桌重新开台） */
    private static final List<String> ACTIVE_TICKET_STATUS = List.of("制作中", "已完成");

    private static final int REASON_MAX = 255;
    private static final int NAME_MAX = 32;

    // ---------------- 结账侧（同事务调用） ----------------

    /**
     * 结账成功后入清洁队列：桌台置「清洁中」、生成待接单清洁单、写交接流水。
     * 调用方已持有账单行锁；本方法再锁桌台行，保证结账与开台/清洁并发时串行。
     */
    @Transactional
    public CleaningTask enqueueFromSettle(TableInfo table, OrderTicket ticket,
                                          String cashier, LocalDateTime now) {
        TableInfo locked = lockTable(table.id);
        if (!"占用".equals(locked.status)) {
            // 防御：只有占用桌台的已完成账单能结账转清洁
            throw new BizException("桌台当前不是「占用」状态，不能转入清洁队列（当前状态：" + locked.status + "）");
        }
        List<CleaningTask> active = taskRepo.findByTableIdAndStatusIn(
                table.id, Dict.CLEANING_ACTIVE_STATUS);
        if (!active.isEmpty()) {
            throw new BizException("该桌台已有未完成的清洁单，不能重复转入清洁");
        }
        locked.status = "清洁中";
        tableRepo.save(locked);

        CleaningTask task = new CleaningTask();
        task.tableId = locked.id;
        task.tableNo = locked.tableNo;
        task.ticketId = ticket == null ? null : ticket.id;
        task.cashier = trimToNull(cashier);
        task.status = "待接单";
        task.queuedAt = now;
        task = taskRepo.save(task);

        writeLog(task, "结账转清洁", task.cashier, "待接单",
                ticket == null ? null : "账单 #" + ticket.id + " 已结账，等待清洁人员接单");
        return task;
    }

    // ---------------- 清洁人员侧 ----------------

    /**
     * 接单：待接单 → 清洁中，记录责任人、开始时间、预计完成时间。
     * 先锁清洁单、再锁桌台：若桌台已被下一桌开台（有新账单），拒绝接单。
     */
    @Transactional
    public CleaningTaskView claim(Long id, ClaimRequest req) {
        String cleaner = requireName(req == null ? null : req.cleaner(), "请填写清洁责任人姓名");
        LocalDateTime eta = parseEta(req == null ? null : req.etaAt());

        CleaningTask task = lockTask(id);
        TableInfo table = lockTable(task.tableId);
        ensureTableStillCleaning(task, table);
        ensureNoNewTicket(task, table, "接单");
        if (!"待接单".equals(task.status)) {
            if ("清洁中".equals(task.status)) {
                throw new BizException("该清洁单已被「" + task.cleaner + "」接单，不能重复接单");
            }
            throw new BizException("该清洁单已完成，不能再次接单");
        }

        LocalDateTime now = LocalDateTime.now();
        task.status = "清洁中";
        task.cleaner = cleaner;
        task.startedAt = now;
        task.etaAt = eta;
        taskRepo.save(task);
        writeLog(task, "接单", cleaner, "清洁中",
                "开始清洁，预计完成 " + fmt(eta));
        return view(task, table);
    }

    /**
     * 暂存未完成原因：仅「清洁中」可存，记录责任人与时间；只更新原因，
     * 不推进状态。页面刷新后原因、责任人、时间都从清洁单读回。
     * 清洁单行锁保证与完成/撤回并发时失败的暂存不会覆盖最新原因。
     */
    @Transactional
    public CleaningTaskView saveReason(Long id, SaveReasonRequest req) {
        String cleaner = requireName(req == null ? null : req.cleaner(), "请填写清洁责任人姓名");
        String reason = req == null || req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new BizException("请填写未完成原因后再暂存");
        }
        if (reason.length() > REASON_MAX) {
            throw new BizException("未完成原因不能超过 " + REASON_MAX + " 个字符");
        }

        CleaningTask task = lockTask(id);
        if (!"清洁中".equals(task.status)) {
            if ("待接单".equals(task.status)) {
                throw new BizException("清洁单尚未接单，暂不能暂存原因");
            }
            throw new BizException("清洁已完成，未完成原因不能再修改");
        }
        if (!cleaner.equals(task.cleaner)) {
            throw new BizException("只有当前责任人「" + task.cleaner + "」可以暂存原因，不能顶替他人的清洁单");
        }
        task.pendingReason = reason;
        task.reasonSavedAt = LocalDateTime.now();
        taskRepo.save(task);
        writeLog(task, "暂存原因", cleaner, "清洁中", reason);
        return view(task);
    }

    /**
     * 完成清洁：清洁中 → 已完成，桌台「清洁中 → 空闲」。
     * 锁顺序固定为 清洁单行 → 桌台行；桌台若已有下一桌的新账单，拒绝完成
     * （新账单在途期间旧清洁单保持清洁中，由管理员/撤回流程处理）。
     */
    @Transactional
    public CleaningTaskView complete(Long id, CompleteRequest req) {
        String cleaner = requireName(req == null ? null : req.cleaner(), "请填写清洁责任人姓名");

        CleaningTask task = lockTask(id);
        if ("已完成".equals(task.status)) {
            throw new BizException("该桌台清洁已完成，请勿重复点击（可刷新查看最新状态）");
        }
        if ("待接单".equals(task.status)) {
            throw new BizException("清洁单尚待接单，不能直接完成；请先由清洁人员接单");
        }
        if (!cleaner.equals(task.cleaner)) {
            throw new BizException("只有当前责任人「" + task.cleaner + "」可以完成清洁，不能顶替他人的清洁单");
        }
        TableInfo table = lockTable(task.tableId);
        ensureTableStillCleaning(task, table);
        ensureNoNewTicket(task, table, "完成清洁");

        LocalDateTime now = LocalDateTime.now();
        task.status = "已完成";
        task.finishedAt = now;
        taskRepo.save(task);

        table.status = "空闲";
        tableRepo.save(table);

        writeLog(task, "完成清洁", cleaner, "已完成",
                task.pendingReason == null ? null : "收尾前未完成原因：" + task.pendingReason);
        return view(task, table);
    }

    /**
     * 撤回清洁：清洁中 → 待接单。责任人清空、开始/预计时间随本次接单失效，
     * 未完成原因保留（异常中断后下一位责任人能看到并继续处理），桌台保持清洁中。
     */
    @Transactional
    public CleaningTask withdraw(Long id, WithdrawRequest req) {
        String cleaner = requireName(req == null ? null : req.cleaner(), "请填写清洁责任人姓名");

        CleaningTask task = lockTask(id);
        if ("待接单".equals(task.status)) {
            throw new BizException("清洁单已在待接单队列，无需撤回");
        }
        if ("已完成".equals(task.status)) {
            throw new BizException("清洁已完成，不能撤回（可刷新查看最新状态）");
        }
        if (!cleaner.equals(task.cleaner)) {
            throw new BizException("只有当前责任人「" + task.cleaner + "」可以撤回清洁");
        }
        TableInfo table = lockTable(task.tableId);
        ensureTableStillCleaning(task, table);
        ensureNoNewTicket(task, table, "撤回清洁");

        String previousCleaner = task.cleaner;
        String retainedReason = task.pendingReason;
        task.status = "待接单";
        task.cleaner = null;
        task.startedAt = null;
        task.etaAt = null;
        taskRepo.save(task);

        writeLog(task, "撤回清洁", previousCleaner, "待接单",
                "责任人「" + previousCleaner + "」撤回，清洁单重回待接单队列"
                        + (retainedReason == null ? "" : "；保留未完成原因：" + retainedReason));
        return view(task, table);
    }

    // ---------------- 查询侧 ----------------

    /** 清洁队列：待接单在前、清洁中在后，同桌附当前桌台状态与责任人等 */
    @Transactional(readOnly = true)
    public List<CleaningTaskView> queue() {
        List<CleaningTask> tasks = taskRepo.findByStatusInOrderByQueuedAtAsc(
                Dict.CLEANING_ACTIVE_STATUS);
        Map<Long, TableInfo> tableMap = loadTables(tasks);
        return tasks.stream().map(t -> view(t, tableMap.get(t.tableId))).toList();
    }

    /** 全部清洁单（管理员用，含已完成的历史单），新的在前 */
    @Transactional(readOnly = true)
    public List<CleaningTaskView> listAll() {
        List<CleaningTask> tasks = taskRepo.findAllByOrderByIdDesc();
        Map<Long, TableInfo> tableMap = loadTables(tasks);
        return tasks.stream().map(t -> view(t, tableMap.get(t.tableId))).toList();
    }

    @Transactional(readOnly = true)
    public CleaningTaskView detail(Long id) {
        CleaningTask task = taskRepo.findById(id).orElseThrow(() -> new BizException("清洁单不存在"));
        return view(task, tableRepo.findById(task.tableId).orElse(null));
    }

    /** 某桌台当前（最近一张）清洁单，无则 null；桌台卡片/列表展示责任人与时间 */
    @Transactional(readOnly = true)
    public CleaningTaskView latestByTable(Long tableId) {
        return taskRepo.findFirstByTableIdOrderByIdDesc(tableId)
                .map(t -> view(t, tableRepo.findById(t.tableId).orElse(null)))
                .orElse(null);
    }

    /** 单张清洁单的交接流水（接单/暂存/完成/撤回全过程，按时间正序） */
    @Transactional(readOnly = true)
    public List<CleaningLogView> logsOfTask(Long taskId) {
        if (!taskRepo.existsById(taskId)) {
            throw new BizException("清洁单不存在");
        }
        return logRepo.findByTaskIdOrderByIdAsc(taskId).stream().map(this::logView).toList();
    }

    /** 某桌台的全部历史交接流水（最近在前） */
    @Transactional(readOnly = true)
    public List<CleaningLogView> logsOfTable(Long tableId) {
        return logRepo.findByTableIdOrderByIdDesc(tableId).stream().map(this::logView).toList();
    }

    /** 管理员看板：当前清洁队列 + 最近交接记录（默认最近 50 条） */
    @Transactional(readOnly = true)
    public CleaningDashboard dashboard() {
        List<CleaningTaskView> q = queue();
        List<CleaningLogView> recent = logRepo.findTop50ByOrderByIdDesc().stream()
                .map(this::logView).toList();
        return new CleaningDashboard(q, recent);
    }

    // ---------------- 内部方法 ----------------

    private CleaningTask lockTask(Long id) {
        List<CleaningTask> locked = taskRepo.findByIdForUpdate(id);
        if (locked.isEmpty()) {
            throw new BizException("清洁单不存在");
        }
        return locked.get(0);
    }

    private TableInfo lockTable(Long id) {
        List<TableInfo> locked = tableRepo.findByIdForUpdate(id);
        if (locked.isEmpty()) {
            throw new BizException("桌台不存在");
        }
        return locked.get(0);
    }

    /** 桌台必须仍是「清洁中」：若已被其他流程提前释放/占用，拒绝本次推进 */
    private void ensureTableStillCleaning(CleaningTask task, TableInfo table) {
        if (!"清洁中".equals(table.status)) {
            throw new BizException("桌台当前为「" + table.status + "」，不在清洁中，本次操作已被拦截（请刷新查看最新状态）");
        }
    }

    /**
     * 关键拦截：桌台已有新的未结账账单（收银员已给下一桌开台/点单）时，
     * 清洁人员不能再对旧清洁单做接单/完成/撤回——旧单不允许把桌台重新标记。
     */
    private void ensureNoNewTicket(CleaningTask task, TableInfo table, String action) {
        List<OrderTicket> active = ticketRepo.findByTableIdAndStatusIn(table.id, ACTIVE_TICKET_STATUS);
        if (!active.isEmpty()) {
            throw new BizException("桌台「" + table.tableNo + "」已有新账单（#"
                    + active.get(0).id + "），不能对旧清洁单执行「" + action
                    + "」；该清洁单保持原状，请联系管理员核对");
        }
    }

    private void writeLog(CleaningTask task, String action, String actor,
                          String taskStatus, String remark) {
        CleaningLog log = new CleaningLog();
        log.taskId = task.id;
        log.tableId = task.tableId;
        log.tableNo = task.tableNo;
        log.ticketId = task.ticketId;
        log.action = action;
        log.actor = actor;
        log.taskStatus = taskStatus;
        log.remark = trimToNull(remark);
        log.createdAt = LocalDateTime.now();
        logRepo.save(log);
    }

    private Map<Long, TableInfo> loadTables(List<CleaningTask> tasks) {
        List<Long> ids = tasks.stream().map(t -> t.tableId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return tableRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(t -> t.id, t -> t));
    }

    private CleaningTaskView view(CleaningTask t) {
        return view(t, tableRepo.findById(t.tableId).orElse(null));
    }

    private CleaningTaskView view(CleaningTask t, TableInfo table) {
        Long activeTicketId = null;
        boolean blockedByNewTicket = false;
        if (table != null) {
            List<OrderTicket> active = ticketRepo.findByTableIdAndStatusIn(
                    table.id, ACTIVE_TICKET_STATUS);
            if (!active.isEmpty()) {
                activeTicketId = active.get(0).id;
                blockedByNewTicket = true;
            }
        }
        return new CleaningTaskView(
                t.id, t.tableId, t.tableNo, t.ticketId, t.cashier,
                t.status, t.cleaner, t.queuedAt, t.startedAt, t.etaAt,
                t.finishedAt, t.pendingReason, t.reasonSavedAt,
                table == null ? null : table.status,
                table == null ? null : table.seats,
                activeTicketId, blockedByNewTicket);
    }

    private CleaningLogView logView(CleaningLog l) {
        return new CleaningLogView(l.id, l.taskId, l.tableId, l.tableNo, l.ticketId,
                l.action, l.actor, l.taskStatus, l.remark, l.createdAt);
    }

    private String requireName(String name, String emptyTip) {
        String n = trimToNull(name);
        if (n == null) {
            throw new BizException(emptyTip);
        }
        if (n.length() > NAME_MAX) {
            throw new BizException("姓名不能超过 " + NAME_MAX + " 个字符");
        }
        return n;
    }

    private LocalDateTime parseEta(String eta) {
        if (eta == null || eta.isBlank()) {
            throw new BizException("请选择预计完成时间");
        }
        try {
            // 前端 el-date-picker 提交 "yyyy-MM-ddTHH:mm"（秒可省略）
            LocalDateTime parsed = LocalDateTime.parse(eta.length() == 16 ? eta + ":00" : eta);
            if (!parsed.isAfter(LocalDateTime.now())) {
                throw new BizException("预计完成时间必须晚于当前时间");
            }
            return parsed;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("预计完成时间格式有误，请重新选择");
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "" : t.toString().replace('T', ' ').substring(0, 16);
    }

    // ---------------- 请求 / 响应记录 ----------------

    public record ClaimRequest(String cleaner, String etaAt) {
    }

    public record SaveReasonRequest(String cleaner, String reason) {
    }

    public record CompleteRequest(String cleaner) {
    }

    public record WithdrawRequest(String cleaner) {
    }

    /** 清洁单视图：附桌台实时状态与「是否被新账单拦截」，前端据此展示可执行动作/拦截原因 */
    public record CleaningTaskView(
            Long id, Long tableId, String tableNo, Long ticketId, String cashier,
            String status, String cleaner, LocalDateTime queuedAt,
            LocalDateTime startedAt, LocalDateTime etaAt, LocalDateTime finishedAt,
            String pendingReason, LocalDateTime reasonSavedAt,
            String tableStatus, Integer tableSeats,
            Long activeTicketId, boolean blockedByNewTicket) {
    }

    public record CleaningLogView(
            Long id, Long taskId, Long tableId, String tableNo, Long ticketId,
            String action, String actor, String taskStatus,
            String remark, LocalDateTime createdAt) {
    }

    public record CleaningDashboard(List<CleaningTaskView> queue,
                                    List<CleaningLogView> recentLogs) {
    }
}
