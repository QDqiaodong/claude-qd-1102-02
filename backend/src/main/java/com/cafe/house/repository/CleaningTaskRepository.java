package com.cafe.house.repository;

import com.cafe.house.entity.CleaningTask;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

public interface CleaningTaskRepository extends JpaRepository<CleaningTask, Long> {

    /** 清洁队列：未完成（待接单/清洁中）的清洁单，入队早的排前面 */
    List<CleaningTask> findByStatusInOrderByQueuedAtAsc(List<String> statuses);

    /** 某桌台的未完成清洁单（结账时校验，理论上同桌只允许一张） */
    List<CleaningTask> findByTableIdAndStatusIn(Long tableId, List<String> statuses);

    Optional<CleaningTask> findFirstByTableIdOrderByIdDesc(Long tableId);

    List<CleaningTask> findAllByOrderByIdDesc();

    /**
     * 清洁单状态推进前先对单行加写锁：清洁人员同时点「完成清洁/撤回清洁」、
     * 收银员同时点「开台」时，事务在库层串行，后到者拿锁后看到的已是最新状态，
     * 业务校验直接拒绝——责任人、开始时间、未完成原因都不会被失败操作覆盖。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from CleaningTask t where t.id = :id")
    List<CleaningTask> findByIdForUpdate(@Param("id") Long id);
}
