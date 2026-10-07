package com.cafe.house.repository;

import com.cafe.house.entity.CleaningHandover;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CleaningHandoverRepository extends JpaRepository<CleaningHandover, Long> {

    List<CleaningHandover> findByTaskIdOrderByIdAsc(Long taskId);

    List<CleaningHandover> findTop20ByOrderByIdDesc();

    Optional<CleaningHandover> findFirstByTableIdOrderByIdDesc(Long tableId);
}
