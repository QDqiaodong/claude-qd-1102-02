package com.cafe.house.repository;

import com.cafe.house.entity.MenuItem;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    boolean existsByName(String name);

    List<MenuItem> findByStatus(String status);
}
