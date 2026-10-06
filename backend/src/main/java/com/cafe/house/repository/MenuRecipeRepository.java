package com.cafe.house.repository;

import com.cafe.house.entity.MenuRecipe;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuRecipeRepository extends JpaRepository<MenuRecipe, Long> {

    List<MenuRecipe> findByMenuItemId(Long menuItemId);

    List<MenuRecipe> findByMenuItemIdIn(List<Long> menuItemIds);

    void deleteByMenuItemId(Long menuItemId);
}
