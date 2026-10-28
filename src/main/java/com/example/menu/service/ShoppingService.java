package com.example.menu.service;

import com.example.menu.domain.Dish;
import com.example.menu.domain.DishRepository;
import com.example.menu.domain.ShoppingEntry;
import com.example.menu.domain.ShoppingEntryRepository;
import com.example.menu.domain.ShoppingIngredient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShoppingService {
    private final DishRepository dishes;
    private final ShoppingEntryRepository entries;

    public ShoppingService(DishRepository dishes, ShoppingEntryRepository entries) {
        this.dishes = dishes;
        this.entries = entries;
    }

    @Transactional(readOnly = true)
    public List<EntryView> list(String owner) {
        return entries.findByOwnerOrderByAddedAtAscIdAsc(owner).stream().map(EntryView::from).toList();
    }

    @Transactional
    public EntryView add(String owner, Long dishId) {
        if (dishId == null || dishId < 1) throw ApiException.badRequest("请选择菜品");
        Dish dish = lockDish(dishId);
        ShoppingEntry entry = entries.findByOwnerAndDishId(owner, dishId)
                .orElseGet(() -> entries.save(new ShoppingEntry(owner, dish)));
        entries.flush();
        return EntryView.from(entry);
    }

    @Transactional
    public EntryView check(String owner, UUID entryId, UUID ingredientId, Boolean checked) {
        if (checked == null) throw ApiException.badRequest("请提供原料勾选状态");
        ShoppingEntry entry = ownedEntryForUpdate(owner, entryId);
        ShoppingIngredient ingredient = entry.getIngredients().stream().filter(item -> item.getId().equals(ingredientId))
                .findFirst().orElseThrow(() -> ApiException.conflict("原料已更新，请刷新待采购清单"));
        ingredient.setChecked(checked);
        entries.flush();
        return EntryView.from(entry);
    }

    @Transactional
    public void remove(String owner, UUID entryId) {
        ShoppingEntry entry = ownedEntryForUpdate(owner, entryId);
        entries.delete(entry);
        entries.flush();
    }

    /** Called with the dish write lock held by MenuService, including approved visitor edits. */
    @Transactional
    public void synchronizeDish(Dish dish) {
        entries.findByDishIdOrderByIdAsc(dish.getId()).forEach(ShoppingEntry::synchronizeIngredients);
        entries.flush();
    }

    /** Remove child rows before deleting a dish; the caller already holds its write lock. */
    @Transactional
    public void removeDish(Dish dish) {
        entries.deleteAll(entries.findByDishIdOrderByIdAsc(dish.getId()));
        entries.flush();
    }

    private ShoppingEntry ownedEntryForUpdate(String owner, UUID id) {
        Long dishId = entries.findDishId(id, owner).orElseThrow(() -> ApiException.notFound("待采购菜品不存在"));
        lockDish(dishId);
        return entries.findByIdAndOwner(id, owner).orElseThrow(() -> ApiException.notFound("待采购菜品不存在"));
    }

    private Dish lockDish(Long id) {
        return dishes.findForUpdate(id).orElseThrow(() -> ApiException.notFound("菜品不存在，请刷新菜单"));
    }

    public record IngredientView(UUID id, String text, boolean checked) {
        static IngredientView from(ShoppingIngredient ingredient) {
            return new IngredientView(ingredient.getId(), ingredient.getText(), ingredient.isChecked());
        }
    }

    public record EntryView(UUID id, MenuViews.DishView dish, List<IngredientView> ingredients, Instant addedAt) {
        static EntryView from(ShoppingEntry entry) {
            return new EntryView(entry.getId(), MenuViews.DishView.from(entry.getDish()),
                    entry.getIngredients().stream().map(IngredientView::from).toList(), entry.getAddedAt());
        }
    }
}
