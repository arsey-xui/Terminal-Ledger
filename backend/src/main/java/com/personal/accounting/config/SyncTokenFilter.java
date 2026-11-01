package com.personal.accounting.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 共享密钥校验：除健康检查外，所有请求都要带 {@value #HEADER} 头。
 *
 * <p><b>留空就不校验</b>：没配密钥的实例行为与升级前完全一致。这一条是硬要求 ——
 * 否则升级后一瞬间，所有还没填密钥的旧客户端都会被 401 挡在门外。</p>
 *
 * <p>密钥是「整个账本一把」，不是按用户区分：这个后端本来就是单用户局域网部署，
 * 目的只是把同一 Wi-Fi 下的其他人挡在外面。</p>
 */
@Component
public class SyncTokenFilter extends OncePerRequestFilter {

    /** 客户端携带共享密钥的请求头名。 */
    public static final String HEADER = "X-Sync-Token";

    private final SyncProperties props;

    public SyncTokenFilter(SyncProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String expected = props.getSharedSecret();
        if (expected == null || expected.isBlank() || isExempt(request)) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(HEADER);
        if (provided != null && matches(expected.trim(), provided.trim())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"success\":false,\"message\":\"缺少或错误的同步密钥（X-Sync-Token）\",\"data\":null}");
    }

    /** 健康检查不校验：部署完要先能探活，这一步不该需要密钥。 */
    private boolean isExempt(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/api/health");
    }

    /**
     * 定长比较。用 {@link MessageDigest#isEqual} 而不是 {@code equals}，
     * 这样比较耗时与内容无关，不会从响应时间上泄漏前缀匹配长度。
     */
    private boolean matches(String expected, String provided) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
