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

    List<CleaningTask> findByStatusInOrderByReleasedAtAsc(List<String> statuses);

    Optional<CleaningTask> findFirstByTableIdAndStatusInOrderByReleasedAtDesc(Long tableId, List<String> statuses);

    List<CleaningTask> findByTableIdInAndStatusIn(List<Long> tableIds, List<String> statuses);

    Optional<CleaningTask> findFirstByTableIdOrderByIdDesc(Long tableId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from CleaningTask t where t.id = :id")
    List<CleaningTask> findByIdForUpdate(@Param("id") Long id);
}
