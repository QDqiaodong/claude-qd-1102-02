package com.cafe.house.repository;

import com.cafe.house.entity.OrderItem;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByTicketId(Long ticketId);
}
