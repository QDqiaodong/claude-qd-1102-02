package com.cafe.house.repository;

import com.cafe.house.entity.BeanConsumption;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BeanConsumptionRepository extends JpaRepository<BeanConsumption, Long> {

    List<BeanConsumption> findByTicketIdOrderByIdAsc(Long ticketId);

    /** 批次追溯：管理员查看某批次被哪些账单、哪些饮品消耗。 */
    List<BeanConsumption> findByBatchIdOrderByConsumedAtAscIdAsc(Long batchId);
}
