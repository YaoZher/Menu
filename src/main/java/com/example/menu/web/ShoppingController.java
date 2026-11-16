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
        //注释：@PatchMapping 注解用于处理 HTTP PATCH 请求，表示对指定资源进行部分更新。
        //{entryId} 和 {ingredientId} 是路径变量，用于标识要更新的购物清单条目和对应的食材。
        //当客户端发送 PATCH 请求到 /api/chef/shopping/{entryId}/ingredients/{ingredientId} 时，
        //该方法会被调用，接收请求体中的 CheckRequest 对象，
        //并根据 principal 获取当前用户的身份信息，
        //然后调用 shopping.check 方法来更新购物清单条目中的食材状态。
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
