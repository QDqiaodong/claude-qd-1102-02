package com.cafe.house.repository;

import com.cafe.house.entity.TableInfo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TableInfoRepository extends JpaRepository<TableInfo, Long> {

    boolean existsByTableNo(String tableNo);

    List<TableInfo> findByStatus(String status);
}
