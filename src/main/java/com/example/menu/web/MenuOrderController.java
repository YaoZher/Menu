package com.example.menu.web;

import com.example.menu.service.MenuOrderService;
import com.example.menu.service.MenuOrderService.OrderState;
import com.example.menu.service.MenuOrderService.SaveOrder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chef/dishes/order")
public class MenuOrderController {
    private final MenuOrderService ordering;
    public MenuOrderController(MenuOrderService ordering) { this.ordering = ordering; }

    @GetMapping public OrderState get() { return ordering.state(); } // 获取当前订单状态
    @PutMapping public OrderState save(@RequestBody SaveOrder request) { return ordering.save(request); } // 保存订单
}
