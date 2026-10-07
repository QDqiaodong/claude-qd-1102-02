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

    /** 桌台状态推进时锁住同一行，序列化开台、结账入清洁队列、完成清洁。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TableInfo t where t.id = :id")
    List<TableInfo> findByIdForUpdate(@Param("id") Long id);
}
