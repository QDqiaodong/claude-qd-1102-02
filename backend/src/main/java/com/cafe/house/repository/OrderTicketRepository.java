package com.cafe.house.repository;

import com.cafe.house.entity.OrderTicket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

import jakarta.persistence.LockModeType;

public interface OrderTicketRepository extends JpaRepository<OrderTicket, Long> {

    List<OrderTicket> findByStatus(String status);

    /** 同一桌台处于「制作中 / 已完成」未结账的账单 */
    List<OrderTicket> findByTableIdAndStatusIn(Long tableId, List<String> statuses);

    /**
     * 完成制作时先对账单行加写锁：两名收银员同时点「完成制作」时，
     * 只有一个事务能先拿到锁并把状态推进为已完成；后到者拿锁后看到的已是
     * 「已完成」，业务校验直接拒绝——账单状态只推进一次、流水不重复。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from OrderTicket t where t.id = :id")
    List<OrderTicket> findByIdForUpdate(@Param("id") Long id);
}
