package com.example.menu.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    UserDetailsService users(PasswordEncoder encoder,
            @Value("${app.admin.username}") String username,
            @Value("${app.admin.password}") String password) {
        if (username.isBlank() || !username.equals(username.strip()) || username.length() > 255
                || username.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("MENU_ADMIN_USERNAME 不能有首尾空格、控制字符或超过 255 个字符。");
        }
        if (password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("请设置 MENU_ADMIN_USERNAME 和 MENU_ADMIN_PASSWORD；密码至少 12 个字符，UTF-8 编码不超过 72 字节。");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password)).roles("ADMIN").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper,
            @Value("${app.rate-limit.enabled:true}") boolean rateLimitEnabled) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/admin/**", "/admin", "/admin/", "/admin.html").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/login", "/login.html", "/assets/**", "/favicon.ico", "/api/menu", "/api/csrf", "/media/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/submissions", "/api/auth/login", "/api/auth/logout").permitAll()
                .requestMatchers("/error").permitAll()
                .anyRequest().denyAll());
        http.requestCache(cache -> cache.requestCache(new NullRequestCache()));
        http.formLogin(login -> login.loginPage("/login").loginProcessingUrl("/api/auth/login")
                .successHandler((request, response, auth) -> json(mapper, response, 200, Map.of("username", auth.getName())))
                .failureHandler((request, response, error) -> json(mapper, response, 401, Map.of("message", "账号或密码不正确"))));
        http.logout(logout -> logout.logoutUrl("/api/auth/logout")
                .invalidateHttpSession(true).deleteCookies("JSESSIONID")
                .logoutSuccessHandler((request, response, auth) -> json(mapper, response, 200, Map.of("message", "已退出登录"))));
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) -> {
                    if (request.getRequestURI().startsWith("/api/")) {
                        json(mapper, response, 401, Map.of("message", "请先登录审核后台"));
                    } else { response.sendRedirect("/login"); }
                })
                .accessDeniedHandler((request, response, error) -> json(mapper, response, 403,
                        Map.of("message", "请求验证已失效或无权访问，请刷新页面后重试"))));
        http.headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'"))
                .referrerPolicy(referrer -> referrer.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        if (rateLimitEnabled) http.addFilterBefore(new RequestRateLimitFilter(mapper), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static void json(ObjectMapper mapper, HttpServletResponse response, int status, Object value) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(), value);
    }
}
