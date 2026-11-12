package com.example.menu.web;

import com.example.menu.service.ShoppingService;
import com.example.menu.service.ShoppingService.EntryView;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Visitors select dishes for the configured chef; checklist writes remain in the chef controller. */
@RestController
@RequestMapping("/api/shopping")
public class GuestShoppingController {
    private final ShoppingService shopping;
    private final String chef;

    public GuestShoppingController(ShoppingService shopping, @Value("${app.chef.username}") String chef) {
        this.shopping = shopping;
        this.chef = chef;
    }

    @GetMapping
    public List<EntryView> list() { return shopping.list(chef); }

    @PostMapping
    public EntryView add(@RequestBody ShoppingController.AddRequest request) {
        return shopping.add(chef, request.dishId());
    }
}
