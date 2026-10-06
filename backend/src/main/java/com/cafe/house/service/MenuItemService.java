package com.cafe.house.service;

import com.cafe.house.common.BizException;
import com.cafe.house.common.Dict;
import com.cafe.house.entity.MenuItem;
import com.cafe.house.entity.MenuRecipe;
import com.cafe.house.repository.MenuItemRepository;
import com.cafe.house.repository.MenuRecipeRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class MenuItemService {

    /** 单杯豆量合理上限（克），防止误录。 */
    public static final int MAX_GRAMS_PER_CUP = 200;

    @Autowired
    private MenuItemRepository repo;

    @Autowired
    private MenuRecipeRepository recipeRepo;

    public List<MenuItem> list(String status) {
        return status == null ? repo.findAll() : repo.findByStatus(status);
    }

    public MenuItem create(MenuItem input) {
        if (input.name == null || input.name.isBlank()) {
            throw new BizException("饮品名不能为空");
        }
        if (repo.existsByName(input.name)) {
            throw new BizException("饮品名已存在");
        }
        validatePrice(input.price);
        if (!Dict.isValidMenuCategory(input.category)) {
            throw new BizException("类别必须是 意式 / 手冲 / 非咖啡 之一");
        }
        MenuItem m = new MenuItem();
        m.name = input.name.trim();
        m.price = input.price;
        m.category = input.category;
        m.status = input.status == null ? "在售" : input.status;
        if (!Dict.isValidMenuStatus(m.status)) {
            throw new BizException("状态必须是 在售 / 下架 之一");
        }
        return repo.save(m);
    }

    public MenuItem update(Long id, MenuItem input) {
        MenuItem m = repo.findById(id).orElseThrow(() -> new BizException("饮品不存在"));
        if (input.name != null) {
            String name = input.name.trim();
            if (name.isBlank()) {
                throw new BizException("饮品名不能为空");
            }
            if (!name.equals(m.name) && repo.existsByName(name)) {
                throw new BizException("饮品名已存在");
            }
            m.name = name;
        }
        if (input.price != null) {
            validatePrice(input.price);
            m.price = input.price;
        }
        if (input.category != null) {
            if (!Dict.isValidMenuCategory(input.category)) {
                throw new BizException("类别必须是 意式 / 手冲 / 非咖啡 之一");
            }
            m.category = input.category;
        }
        if (input.status != null) {
            if (!Dict.isValidMenuStatus(input.status)) {
                throw new BizException("状态必须是 在售 / 下架 之一");
            }
            m.status = input.status;
        }
        return repo.save(m);
    }

    // ---------------- 配方维护 ----------------

    public List<RecipeLineView> getRecipe(Long menuItemId) {
        ensureMenuExists(menuItemId);
        List<RecipeLineView> lines = new ArrayList<>();
        for (MenuRecipe r : recipeRepo.findByMenuItemId(menuItemId)) {
            lines.add(new RecipeLineView(r.id, r.beanOrigin, r.grams, r.updatedAt));
        }
        return lines;
    }

    /**
     * 全量保存某饮品配方（整单替换）。
     *
     * <p>牵制口径：配方只描述「每杯需要哪个豆种多少克」，不出现任何批次字段，
     * 管理员也无法在此指定批次；实际扣减哪个批次由完成制作时的 FIFO 分配决定。
     * 非咖啡类允许配方为空；咖啡类若没有配方，完成制作时会被拦下。</p>
     */
    @Transactional
    public List<RecipeLineView> saveRecipe(Long menuItemId, List<RecipeLineInput> inputs) {
        MenuItem m = ensureMenuExists(menuItemId);
        List<RecipeLineInput> lines = inputs == null ? List.of() : inputs;

        Set<String> seen = new HashSet<>();
        for (RecipeLineInput in : lines) {
            if (in.beanOrigin() == null || in.beanOrigin().isBlank()) {
                throw new BizException("配方豆种不能为空");
            }
            String origin = in.beanOrigin().trim();
            if (!seen.add(origin)) {
                throw new BizException("豆种「" + origin + "」在配方中重复，请合并为一行");
            }
            if (in.grams() == null || in.grams() <= 0) {
                throw new BizException("豆种「" + origin + "」每杯克数必须大于 0");
            }
            if (in.grams() > MAX_GRAMS_PER_CUP) {
                throw new BizException("豆种「" + origin + "」每杯克数不能超过 " + MAX_GRAMS_PER_CUP + " 克");
            }
        }
        if (m.category != null && !"非咖啡".equals(m.category) && lines.isEmpty()) {
            // 咖啡类不强制保存配方（允许先建饮品再补配方），但给明确提示
            throw new BizException("「" + m.name + "」是咖啡类饮品，请至少维护一条豆种配方（非咖啡类才允许为空）");
        }

        recipeRepo.deleteByMenuItemId(menuItemId);
        recipeRepo.flush();
        LocalDateTime now = LocalDateTime.now();
        List<RecipeLineView> views = new ArrayList<>();
        for (RecipeLineInput in : lines) {
            MenuRecipe r = new MenuRecipe();
            r.menuItemId = menuItemId;
            r.beanOrigin = in.beanOrigin().trim();
            r.grams = in.grams();
            r.createdAt = now;
            r.updatedAt = now;
            r = recipeRepo.save(r);
            views.add(new RecipeLineView(r.id, r.beanOrigin, r.grams, r.updatedAt));
        }
        return views;
    }

    private MenuItem ensureMenuExists(Long menuItemId) {
        return repo.findById(menuItemId).orElseThrow(() -> new BizException("饮品不存在"));
    }

    private void validatePrice(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("价格必须大于 0");
        }
    }

    // ---------------- 视图记录 ----------------

    public record RecipeLineInput(String beanOrigin, Integer grams) {
    }

    public record RecipeLineView(Long id, String beanOrigin, Integer grams, LocalDateTime updatedAt) {
    }
}
