package com.cafe.house.repository;

import com.cafe.house.entity.TableInfo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

import jakarta.persistence.LockModeType;

public interface TableInfoRepository extends JpaRepository<TableInfo, Long> {

    boolean existsByTableNo(String tableNo);

    List<TableInfo> findByStatus(String status);

    /**
     * 结账释放 / 清洁完成 / 开台时对桌台行加写锁：并发点击时库层串行，
     * 先提交的事务决定最终状态，后到者按最新状态校验并拒绝，杜绝
     * 「清洁还没完成就开台」「完成清洁与开台互相覆盖」。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TableInfo t where t.id = :id")
    List<TableInfo> findByIdForUpdate(@Param("id") Long id);
}
