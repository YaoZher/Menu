package com.example.menu.config;

import java.security.Principal;
import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class PageController {
    @GetMapping({"/admin", "/admin/"})
    String admin() { return "forward:/admin.html"; }

    @GetMapping("/login")
    String login() { return "forward:/login.html"; }

    @GetMapping("/api/csrf")
    @ResponseBody
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @GetMapping("/api/admin/me")
    @ResponseBody
    Map<String, String> me(Principal principal) { return Map.of("username", principal.getName()); }
}
