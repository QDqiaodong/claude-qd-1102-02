package com.cafe.house.service;

import com.cafe.house.common.BizException;
import com.cafe.house.entity.CleaningHandover;
import com.cafe.house.entity.CleaningTask;
import com.cafe.house.entity.OrderTicket;
import com.cafe.house.entity.TableInfo;
import com.cafe.house.repository.CleaningHandoverRepository;
import com.cafe.house.repository.CleaningTaskRepository;
import com.cafe.house.repository.OrderTicketRepository;
import com.cafe.house.repository.TableInfoRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CleaningService {

    private static final List<String> ACTIVE_TASK_STATUS = List.of("待接单", "清洁中");
    private static final List<String> ACTIVE_TICKET_STATUS = List.of("制作中", "已完成");

    @Autowired
    private CleaningTaskRepository taskRepo;

    @Autowired
    private CleaningHandoverRepository handoverRepo;

    @Autowired
    private TableInfoRepository tableRepo;

    @Autowired
    private OrderTicketRepository ticketRepo;

    /** 当前清洁队列：结账释放时间越久越靠前。 */
    @Transactional(readOnly = true)
    public List<TaskView> queue() {
        List<CleaningTask> tasks = taskRepo.findByStatusInOrderByReleasedAtAsc(ACTIVE_TASK_STATUS);
        return buildTaskViews(tasks, true);
    }

    @Transactional(readOnly = true)
    public CleaningOverview overview() {
        List<TaskView> tasks = queue();
        List<HandoverView> recent = handoverRepo.findTop20ByOrderByIdDesc().stream()
                .map(this::toHandoverView)
                .toList();
        return new CleaningOverview(tasks, recent.isEmpty() ? null : recent.get(0), recent);
    }

    @Transactional(readOnly = true)
    public TaskView taskDetail(Long id) {
        CleaningTask task = taskRepo.findById(id).orElseThrow(() -> new BizException("清洁单不存在"));
        return buildTaskViews(List.of(task), true).get(0);
    }

    /**
     * 账单结账成功后调用：桌台占用 → 清洁中，并追加清洁单与交接流水。
     * 调用方已持有账单行锁；这里再锁桌台行，和开台/完成清洁串行化。
     */
    @Transactional
    public TaskView enqueueAfterSettlement(Long tableId, Long ticketId, String cashier, LocalDateTime now) {
        TableInfo table = lockTable(tableId);
        if (!"占用".equals(table.status)) {
            throw new BizException("只有「占用」桌台的已结账账单可以进入清洁队列");
        }
        Optional<CleaningTask> duplicate = taskRepo
                .findFirstByTableIdAndStatusInOrderByReleasedAtDesc(tableId, ACTIVE_TASK_STATUS);
        if (duplicate.isPresent()) {
            throw new BizException("该桌台已有未完成清洁单，不能重复进入清洁队列");
        }
        List<OrderTicket> activeTickets = ticketRepo.findByTableIdAndStatusIn(tableId, ACTIVE_TICKET_STATUS);
        if (!activeTickets.isEmpty()) {
            throw new BizException("该桌台仍有未结账账单，不能进入清洁队列");
        }

        table.status = "清洁中";
        tableRepo.save(table);

        CleaningTask task = new CleaningTask();
        task.tableId = tableId;
        task.ticketId = ticketId;
        task.status = "待接单";
        task.releasedBy = cashier;
        task.releasedAt = now;
        task = taskRepo.save(task);

        appendEvent(task, "结账待清洁", "占用", "清洁中", cashier, null, null, now);
        return buildTaskViews(List.of(task), true).get(0);
    }

    /** 清洁人员接单：待接单 → 清洁中，记录责任人、开始时间和预计完成时间。 */
    @Transactional
    public TaskView accept(Long id, AcceptCleaningRequest req) {
        String cleaner = requiredPerson(req == null ? null : req.cleanerName(), "清洁责任人");
        LocalDateTime expectedAt = parseExpectedAt(req == null ? null : req.expectedFinishAt());
        CleaningTask expectedTask = taskRepo.findById(id).orElseThrow(() -> new BizException("清洁单不存在"));

        TableInfo table = lockTable(expectedTask.tableId);
        CleaningTask task = lockTask(id);
        ensureNoNewTicket(table, task);
        if ("清洁中".equals(task.status)) {
            throw new BizException("该桌台已由「" + task.cleanerName + "」接单，请刷新清洁队列（不能重复接单或覆盖责任人）");
        }
        if ("已完成".equals(task.status)) {
            throw new BizException("该清洁单已完成，桌台当前为「" + table.status + "」，不能再接单");
        }
        if (!"待接单".equals(task.status)) {
            throw new BizException("只有「待接单」清洁单可以接单");
        }
        if (!"清洁中".equals(table.status)) {
            throw new BizException("桌台当前为「" + table.status + "」，不是清洁中，接单请求已被拦截");
        }

        LocalDateTime now = LocalDateTime.now();
        task.status = "清洁中";
        task.cleanerName = cleaner;
        task.acceptedAt = now;
        task.expectedFinishAt = expectedAt;
        taskRepo.save(task);
        appendEvent(task, "接单", "待接单", "清洁中", cleaner, expectedAt, null, now);
        return buildTaskViews(List.of(task), true).get(0);
    }

    /** 暂存未完成原因；只有当前责任人在清洁中可以保存，刷新后从数据库恢复。 */
    @Transactional
    public TaskView saveReason(Long id, SaveReasonRequest req) {
        String cleaner = requiredPerson(req == null ? null : req.cleanerName(), "清洁责任人");
        String reason = normalizeReason(req == null ? null : req.unfinishedReason());
        CleaningTask expectedTask = taskRepo.findById(id).orElseThrow(() -> new BizException("清洁单不存在"));

        TableInfo table = lockTable(expectedTask.tableId);
        CleaningTask task = lockTask(id);
        ensureCurrentCleaner(table, task, cleaner);
        LocalDateTime now = LocalDateTime.now();
        task.unfinishedReason = reason;
        task.reasonUpdatedAt = now;
        taskRepo.save(task);
        appendEvent(task, "暂存原因", "清洁中", "清洁中", cleaner,
                task.expectedFinishAt, reason, now);
        return buildTaskViews(List.of(task), true).get(0);
    }

    /** 完成清洁：清洁中 → 已完成，桌台 → 空闲。有未完成原因时必须先处理并清空。 */
    @Transactional
    public TaskView complete(Long id, CompleteCleaningRequest req) {
        String cleaner = requiredPerson(req == null ? null : req.cleanerName(), "清洁责任人");
        String expectedStatus = req == null ? null : req.expectedStatus();
        CleaningTask expectedTask = taskRepo.findById(id).orElseThrow(() -> new BizException("清洁单不存在"));

        TableInfo table = lockTable(expectedTask.tableId);
        CleaningTask task = lockTask(id);
        if (expectedStatus != null && !expectedStatus.equals(task.status)) {
            throw new BizException("页面状态已过期：清洁单当前为「" + task.status + "」，请刷新后再操作（本次未覆盖责任人与原因）");
        }
        ensureCurrentCleaner(table, task, cleaner);
        if ("已完成".equals(task.status)) {
            throw new BizException("清洁已完成，请勿重复提交；桌台当前为「" + table.status + "」");
        }
        if ("待接单".equals(task.status)) {
            throw new BizException("请先接单并填写预计完成时间，再完成清洁");
        }
        if (task.unfinishedReason != null && !task.unfinishedReason.isBlank()) {
            throw new BizException("仍有未完成原因：「" + task.unfinishedReason + "」；请处理后清空原因暂存，再完成清洁");
        }
        if (!"清洁中".equals(table.status)) {
            throw new BizException("桌台当前为「" + table.status + "」，不能将旧清洁单标记完成");
        }

        LocalDateTime now = LocalDateTime.now();
        task.status = "已完成";
        task.completedAt = now;
        taskRepo.save(task);
        table.status = "空闲";
        tableRepo.save(table);
        appendEvent(task, "完成清洁", "清洁中", "空闲", cleaner,
                task.expectedFinishAt, null, now);
        return buildTaskViews(List.of(task), true).get(0);
    }

    /**
     * 撤回清洁：清洁中退回待接单，但桌台仍保持清洁中。
     * 责任人、开始时间、预计完成时间和原因均保留，供下一位清洁人员接续。
     */
    @Transactional
    public TaskView withdraw(Long id, WithdrawCleaningRequest req) {
        String cleaner = requiredPerson(req == null ? null : req.cleanerName(), "清洁责任人");
        String expectedStatus = req == null ? null : req.expectedStatus();
        String reason = normalizeReason(req == null ? null : req.unfinishedReason());
        CleaningTask expectedTask = taskRepo.findById(id).orElseThrow(() -> new BizException("清洁单不存在"));

        TableInfo table = lockTable(expectedTask.tableId);
        CleaningTask task = lockTask(id);
        if (expectedStatus != null && !expectedStatus.equals(task.status)) {
            throw new BizException("页面状态已过期：清洁单当前为「" + task.status + "」，请刷新后再操作（本次未覆盖责任人与原因）");
        }
        ensureCurrentCleaner(table, task, cleaner);
        if ("待接单".equals(task.status)) {
            throw new BizException("该清洁单已撤回/尚未接单，请勿重复撤回；请刷新清洁队列");
        }
        if ("已完成".equals(task.status)) {
            throw new BizException("该清洁单已完成，不能撤回；桌台当前为「" + table.status + "」");
        }
        if (!"清洁中".equals(table.status)) {
            throw new BizException("桌台已有新状态（" + table.status + "），不能撤回旧清洁单");
        }

        LocalDateTime now = LocalDateTime.now();
        task.status = "待接单";
        if (!reason.isBlank()) {
            task.unfinishedReason = reason;
            task.reasonUpdatedAt = now;
        }
        taskRepo.save(task);
        appendEvent(task, "撤回清洁", "清洁中", "待接单", cleaner,
                task.expectedFinishAt, reason.isBlank() ? task.unfinishedReason : reason, now);
        return buildTaskViews(List.of(task), true).get(0);
    }

    /** 收银员开台：只有空闲桌台可开；清洁中或已有新账单一律拦截。 */
    @Transactional
    public TableInfo openTable(Long id, OpenTableRequest req) {
        String expectedStatus = req == null ? null : req.expectedStatus();
        TableInfo table = lockTable(id);
        if (expectedStatus != null && !expectedStatus.equals(table.status)) {
            throw new BizException("桌台状态刚刚变化为「" + table.status + "」，请刷新列表后再操作");
        }
        Optional<CleaningTask> activeTask = taskRepo
                .findFirstByTableIdAndStatusInOrderByReleasedAtDesc(id, ACTIVE_TASK_STATUS);
        if (activeTask.isPresent()) {
            CleaningTask task = activeTask.get();
            if ("清洁中".equals(task.status)) {
                throw new BizException("桌台正在由「" + task.cleanerName + "」清洁，预计 "
                        + formatTime(task.expectedFinishAt) + " 完成，清洁完成前不能开台");
            }
            throw new BizException("桌台已结账释放，正在等待清洁人员接单，不能绕过清洁直接开台");
        }
        if ("清洁中".equals(table.status)) {
            throw new BizException("桌台清洁尚未完成，不能开台");
        }
        if ("占用".equals(table.status)) {
            throw new BizException("桌台已有账单占用，不能重复开台");
        }
        if (!"空闲".equals(table.status)) {
            throw new BizException("只有「空闲」桌台可以开台");
        }
        // 桌台行已加锁；普通查询足以复核并发开台，真正的状态提交仍由桌台行锁串行化。
        List<OrderTicket> activeTickets = ticketRepo.findByTableIdAndStatusIn(id, ACTIVE_TICKET_STATUS);
        if (!activeTickets.isEmpty()) {
            throw new BizException("该桌台已有未结账账单，不能再次开台");
        }
        table.status = "占用";
        return tableRepo.save(table);
    }

    @Transactional(readOnly = true)
    public List<TableDetail> enrichTables(List<TableInfo> tables) {
        if (tables.isEmpty()) {
            return List.of();
        }
        List<Long> ids = tables.stream().map(t -> t.id).toList();
        List<CleaningTask> activeTasks = taskRepo.findByTableIdInAndStatusIn(ids, ACTIVE_TASK_STATUS);
        Map<Long, TaskView> taskMap = new LinkedHashMap<>();
        for (TaskView view : buildTaskViews(activeTasks, false)) {
            taskMap.put(view.tableId(), view);
        }
        return tables.stream()
                .map(t -> new TableDetail(t.id, t.tableNo, t.seats, t.status, t.createdAt,
                        taskMap.get(t.id)))
                .toList();
    }

    // ---------------- 内部方法 ----------------

    private List<TaskView> buildTaskViews(List<CleaningTask> tasks, boolean includeEvents) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        Map<Long, TableInfo> tables = new LinkedHashMap<>();
        for (TableInfo t : tableRepo.findAllById(tasks.stream().map(x -> x.tableId).distinct().toList())) {
            tables.put(t.id, t);
        }
        Map<Long, OrderTicket> tickets = new LinkedHashMap<>();
        List<Long> ticketIds = tasks.stream().map(x -> x.ticketId).filter(x -> x != null).distinct().toList();
        if (!ticketIds.isEmpty()) {
            for (OrderTicket t : ticketRepo.findAllById(ticketIds)) {
                tickets.put(t.id, t);
            }
        }
        List<TaskView> views = new ArrayList<>();
        for (CleaningTask task : tasks) {
            TableInfo table = tables.get(task.tableId);
            OrderTicket ticket = task.ticketId == null ? null : tickets.get(task.ticketId);
            List<HandoverView> events = includeEvents
                    ? handoverRepo.findByTaskIdOrderByIdAsc(task.id).stream().map(this::toHandoverView).toList()
                    : List.of();
            views.add(new TaskView(task.id, task.tableId,
                    table == null ? "未知桌台" : table.tableNo,
                    table == null ? null : table.status,
                    task.ticketId,
                    ticket == null ? null : ticket.status,
                    task.status, task.releasedBy, task.releasedAt,
                    task.cleanerName, task.acceptedAt, task.expectedFinishAt,
                    task.unfinishedReason, task.reasonUpdatedAt, task.completedAt,
                    events));
        }
        if (includeEvents) {
            views.sort(Comparator.comparing(TaskView::releasedAt));
        }
        return views;
    }

    private void appendEvent(CleaningTask task, String event, String fromStatus, String toStatus,
                             String operator, LocalDateTime expectedAt, String reason,
                             LocalDateTime now) {
        CleaningHandover h = new CleaningHandover();
        h.taskId = task.id;
        h.tableId = task.tableId;
        h.ticketId = task.ticketId;
        h.event = event;
        h.fromStatus = fromStatus;
        h.toStatus = toStatus;
        h.operatorName = operator;
        h.expectedFinishAt = expectedAt;
        h.unfinishedReason = reason;
        h.createdAt = now;
        handoverRepo.save(h);
    }

    private void ensureCurrentCleaner(TableInfo table, CleaningTask task, String cleaner) {
        if (!"清洁中".equals(task.status)) {
            throw new BizException("清洁单当前为「" + task.status + "」，不是清洁中，不能执行该操作");
        }
        if (!"清洁中".equals(table.status)) {
            throw new BizException("桌台当前为「" + table.status + "」，旧清洁操作已被拦截（不会覆盖最新责任人与原因）");
        }
        if (task.cleanerName == null || !task.cleanerName.equals(cleaner)) {
            throw new BizException("当前清洁责任人是「" + task.cleanerName + "」；只有责任人本人可以暂存原因、撤回或完成，不能覆盖其记录");
        }
    }

    private void ensureNoNewTicket(TableInfo table, CleaningTask task) {
        if (task.ticketId != null) {
            List<OrderTicket> active = ticketRepo.findByTableIdAndStatusIn(table.id, ACTIVE_TICKET_STATUS);
            boolean newer = active.stream().anyMatch(t -> !t.id.equals(task.ticketId));
            if (newer) {
                throw new BizException("该桌台已有新账单，不能重新接单或改回清洁中");
            }
        }
        if (!"清洁中".equals(table.status)) {
            throw new BizException("桌台当前为「" + table.status + "」，已有新交接，不能操作旧清洁单");
        }
    }

    private TableInfo lockTable(Long id) {
        List<TableInfo> locked = tableRepo.findByIdForUpdate(id);
        if (locked.isEmpty()) {
            throw new BizException("桌台不存在");
        }
        return locked.get(0);
    }

    private CleaningTask lockTask(Long id) {
        List<CleaningTask> locked = taskRepo.findByIdForUpdate(id);
        if (locked.isEmpty()) {
            throw new BizException("清洁单不存在");
        }
        return locked.get(0);
    }

    private String requiredPerson(String value, String label) {
        String s = value == null ? null : value.trim();
        if (s == null || s.isEmpty()) {
            throw new BizException("请填写" + label);
        }
        if (s.length() > 32) {
            throw new BizException(label + "不能超过 32 个字符");
        }
        return s;
    }

    private String normalizeReason(String value) {
        if (value == null) {
            return "";
        }
        String s = value.trim();
        if (s.length() > 500) {
            throw new BizException("未完成原因不能超过 500 个字符");
        }
        return s;
    }

    private LocalDateTime parseExpectedAt(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException("请选择预计完成时间");
        }
        try {
            LocalDateTime at = LocalDateTime.parse(value.trim());
            if (!at.isAfter(LocalDateTime.now())) {
                throw new BizException("预计完成时间必须晚于当前时间");
            }
            return at;
        } catch (java.time.format.DateTimeParseException e) {
            throw new BizException("预计完成时间格式不正确");
        }
    }

    private String formatTime(LocalDateTime t) {
        return t == null ? "—" : t.toString().replace('T', ' ').substring(0, 16);
    }

    private HandoverView toHandoverView(CleaningHandover h) {
        return new HandoverView(h.id, h.taskId, h.tableId, h.ticketId, h.event,
                h.fromStatus, h.toStatus, h.operatorName, h.expectedFinishAt,
                h.unfinishedReason, h.createdAt);
    }

    // ---------------- 请求 / 响应记录 ----------------

    public record AcceptCleaningRequest(String cleanerName, String expectedFinishAt) {
    }

    public record SaveReasonRequest(String cleanerName, String unfinishedReason) {
    }

    public record CompleteCleaningRequest(String cleanerName, String expectedStatus) {
    }

    public record WithdrawCleaningRequest(String cleanerName, String expectedStatus,
                                          String unfinishedReason) {
    }

    public record OpenTableRequest(String expectedStatus) {
    }

    public record HandoverView(Long id, Long taskId, Long tableId, Long ticketId, String event,
                               String fromStatus, String toStatus, String operatorName,
                               LocalDateTime expectedFinishAt, String unfinishedReason,
                               LocalDateTime createdAt) {
    }

    public record TaskView(Long id, Long tableId, String tableNo, String tableStatus,
                           Long ticketId, String ticketStatus, String status,
                           String releasedBy, LocalDateTime releasedAt,
                           String cleanerName, LocalDateTime acceptedAt,
                           LocalDateTime expectedFinishAt, String unfinishedReason,
                           LocalDateTime reasonUpdatedAt, LocalDateTime completedAt,
                           List<HandoverView> events) {
    }

    public record TableDetail(Long id, String tableNo, Integer seats, String status,
                              LocalDateTime createdAt, TaskView activeCleaning) {
    }

    public record CleaningOverview(List<TaskView> queue, HandoverView latestHandover,
                                   List<HandoverView> recentHandovers) {
    }
}
