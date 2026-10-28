package com.example.menu.web;

import com.example.menu.service.ShoppingService;
import com.example.menu.service.ShoppingService.EntryView;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chef/shopping")
public class ShoppingController {
    private final ShoppingService shopping;

    public ShoppingController(ShoppingService shopping) { this.shopping = shopping; }

    @GetMapping
    public List<EntryView> list(Principal principal) { return shopping.list(principal.getName()); }

    @PostMapping
    public EntryView add(@RequestBody AddRequest request, Principal principal) {
        return shopping.add(principal.getName(), request.dishId());
    }

    @PatchMapping("/{entryId}/ingredients/{ingredientId}")
    public EntryView check(@PathVariable UUID entryId, @PathVariable UUID ingredientId,
                           @RequestBody CheckRequest request, Principal principal) {
        return shopping.check(principal.getName(), entryId, ingredientId, request.checked());
    }

    @DeleteMapping("/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID entryId, Principal principal) {
        shopping.remove(principal.getName(), entryId);
    }

    public record AddRequest(Long dishId) { }
    public record CheckRequest(Boolean checked) { }
}
