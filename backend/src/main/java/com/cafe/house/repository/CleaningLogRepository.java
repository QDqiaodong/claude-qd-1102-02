package com.cafe.house.repository;

import com.cafe.house.entity.CleaningLog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CleaningLogRepository extends JpaRepository<CleaningLog, Long> {

    List<CleaningLog> findByTaskIdOrderByIdAsc(Long taskId);

    /** 某桌台的全部历史交接流水（最近在后/前由调用方排序取用） */
    List<CleaningLog> findByTableIdOrderByIdDesc(Long tableId);

    List<CleaningLog> findTop50ByOrderByIdDesc();
}
