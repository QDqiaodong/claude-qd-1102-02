package com.cafe.house.repository;

import com.cafe.house.entity.BeanBatch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

import jakarta.persistence.LockModeType;

public interface BeanBatchRepository extends JpaRepository<BeanBatch, Long> {

    boolean existsByBatchNo(String batchNo);

    List<BeanBatch> findByStatus(String status);

    /**
     * 一次性取出本次分配涉及的所有「已入库 且 有余量」批次并加行写锁（FOR UPDATE）。
     *
     * <p>注意：SQL 层故意按主键 id 升序加锁——所有事务对批次的加锁顺序全局一致，
     * 杜绝两张账单各持一批再互等的跨账单死锁；FIFO 的入库先后顺序在内存中排序。
     * 并发账单扣减同一批次时，后到者在行锁上排队，读到的是扣减后的余量，从根上防超扣。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BeanBatch b where b.status = '已入库' and b.remainingG > 0 "
            + "and b.origin in :origins order by b.id asc")
    List<BeanBatch> findAvailableForUpdateByOrigins(@Param("origins") List<String> origins);

    /** 无锁版本：仅用于库存预览/点单提示，绝不能用于扣减决策（调用方在内存中按 FIFO 重排）。 */
    @Query("select b from BeanBatch b where b.status = '已入库' and b.remainingG > 0")
    List<BeanBatch> findAvailable();
}
