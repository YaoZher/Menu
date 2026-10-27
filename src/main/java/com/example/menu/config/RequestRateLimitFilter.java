package com.example.menu.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/** 单实例限流；只信任连接来源，不接受访客伪造的 X-Forwarded-For。 */
final class RequestRateLimitFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final Map<String, Window> windows = new LinkedHashMap<>(128, .75f, true);
    private record Window(long started, int count) { }

    RequestRateLimitFilter(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        boolean login = path.equals("/api/chef/login");
        if (request.getMethod().equals("POST") && (login || path.equals("/api/submissions"))) {
            long duration = (login ? Duration.ofMinutes(1) : Duration.ofHours(1)).toMillis();
            int limit = login ? 10 : 30;
            long retry = reserve(path + ":" + request.getRemoteAddr(), duration, limit);
            if (retry > 0) {
                response.setHeader("Retry-After", Long.toString(retry));
                SecurityConfig.json(mapper, response, 429, Map.of("message", "操作太频繁，请稍后再试"));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private synchronized long reserve(String key, long duration, int limit) {
        long now = System.currentTimeMillis();
        Window window = windows.get(key);
        if (window == null || now - window.started() >= duration) window = new Window(now, 0);
        if (window.count() >= limit) return Math.max(1, (duration - (now - window.started())) / 1000 + 1);
        windows.put(key, new Window(window.started(), window.count() + 1));
        if (windows.size() > 10_000) windows.remove(windows.keySet().iterator().next());
        return 0;
    }
}
