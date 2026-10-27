package com.example.menu.web;

import com.example.menu.service.ApiException;
import com.example.menu.service.MenuService;
import com.example.menu.service.MenuViews.SubmissionView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/chef/submissions")
public class ReviewController {
    private final MenuService menu;

    public ReviewController(MenuService menu) { this.menu = menu; }

    @GetMapping
    public List<SubmissionView> list(@RequestParam(defaultValue = "PENDING") String status) {
        return menu.listSubmissions(status);
    }

    @PostMapping("/{id}/approve")
    public SubmissionView approve(@PathVariable Long id, @RequestBody(required = false) ReviewRequest request,
                                  Principal principal) {
        return menu.approve(id, request == null ? null : request.note(), reviewer(principal));
    }

    @PostMapping("/{id}/reject")
    public SubmissionView reject(@PathVariable Long id, @RequestBody(required = false) ReviewRequest request,
                                 Principal principal) {
        return menu.reject(id, request == null ? null : request.note(), reviewer(principal));
    }

    private String reviewer(Principal principal) {
        if (principal == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录厨师后台");
        String username = principal.getName();
        if (username == null || username.isBlank() || username.length() > 255) {
            throw ApiException.badRequest("审核账号名称无效");
        }
        return username;
    }

    public record ReviewRequest(String note) { }
}
