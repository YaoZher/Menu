package com.example.menu.service;

import com.example.menu.domain.Dish;
import com.example.menu.domain.DishRepository;
import com.example.menu.domain.MenuOrder;
import com.example.menu.domain.MenuOrderRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenuOrderService {
    private final MenuOrderRepository orders;
    private final DishRepository dishes;

    public MenuOrderService(MenuOrderRepository orders, DishRepository dishes) {
        this.orders = orders;
        this.dishes = dishes;
    }

    public record OrderState(List<Long> dishIds, long revision) { }
    public record SaveOrder(List<Long> dishIds, List<Long> expectedDishIds, Long expectedRevision) { }

    @Transactional(readOnly = true)
    public List<Dish> listDishes() {
        MenuOrder order = orders.findById(MenuOrder.SINGLETON_ID).orElseThrow();
        return effectiveOrder(order);
    }

    @Transactional
    public OrderState state() {
        MenuOrder order = lockForMenuChange();
        return state(order);
    }

    @Transactional
    public OrderState save(SaveOrder request) {
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0) {
            throw ApiException.badRequest("请提供有效的菜单版本");
        }
        validateIds(request.dishIds());
        validateIds(request.expectedDishIds());
        if (!new HashSet<>(request.dishIds()).equals(new HashSet<>(request.expectedDishIds()))) {
            throw ApiException.badRequest("排序需包含全部菜品，不能添加或移除菜品");
        }
        MenuOrder order = lockForMenuChange();
        OrderState current = state(order);
        if (current.revision() != request.expectedRevision() || !current.dishIds().equals(request.expectedDishIds())) {
            throw ApiException.conflict("菜单已发生变化，请撤销后重新开启排序");
        }
        order.replace(request.dishIds());
        orders.flush();
        return new OrderState(List.copyOf(request.dishIds()), order.getRevision());
    }

    /** Take this lock before approval locks so additions/deletions cannot race a sort save. */
    @Transactional
    public MenuOrder lockForMenuChange() { return orders.findForUpdate().orElseThrow(); }

    private OrderState state(MenuOrder order) {
        return new OrderState(effectiveOrder(order).stream().map(Dish::getId).toList(), order.getRevision());
    }

    private List<Dish> effectiveOrder(MenuOrder order) {
        List<Dish> current = dishes.findAllByOrderByUpdatedAtDescIdDesc();
        if (!order.isConfigured()) return current;
        var remaining = new HashMap<Long, Dish>();
        current.forEach(dish -> remaining.put(dish.getId(), dish));
        var result = new ArrayList<Dish>(current.size());
        for (Long id : order.dishIds()) {
            Dish dish = remaining.remove(id);
            if (dish != null) result.add(dish);
        }
        // ID order gives newly published dishes stable append positions even after later edits.
        remaining.values().stream().sorted(Comparator.comparing(Dish::getId)).forEach(result::add);
        return result;
    }

    private static void validateIds(List<Long> ids) {
        if (ids == null || ids.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(ids).size() != ids.size()) {
            throw ApiException.badRequest("菜品编号必须为不重复的正整数");
        }
    }
}
