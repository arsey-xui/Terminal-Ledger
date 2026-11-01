package com.personal.accounting.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 共享密钥校验的四种情形，对应验收标准。 */
class SyncTokenFilterTest {

    private static final String SECRET = "s3cret";

    private final AtomicBoolean passedThrough = new AtomicBoolean();

    private final FilterChain chain = (req, resp) -> passedThrough.set(true);

    private MockHttpServletResponse run(String secret, String path, String header) throws Exception {
        SyncProperties props = new SyncProperties();
        props.setSharedSecret(secret);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        if (header != null) request.addHeader(SyncTokenFilter.HEADER, header);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new SyncTokenFilter(props).doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("未配置密钥：不校验，请求照常放行")
    void blankSecretChangesNothing() throws Exception {
        MockHttpServletResponse resp = run("", "/api/sync/push", null);
        assertEquals(200, resp.getStatus());
        assertTrue(passedThrough.get(), "留空时必须放行，否则升级瞬间旧客户端全被挡掉");
    }

    @Test
    @DisplayName("配置了密钥但请求没带：401，且不进业务逻辑")
    void missingTokenIsRejected() throws Exception {
        MockHttpServletResponse resp = run(SECRET, "/api/sync/push", null);
        assertEquals(401, resp.getStatus());
        assertFalse(passedThrough.get());
    }

    @Test
    @DisplayName("配置了密钥但请求带错：401")
    void wrongTokenIsRejected() throws Exception {
        MockHttpServletResponse resp = run(SECRET, "/api/sync/pull", "nope");
        assertEquals(401, resp.getStatus());
        assertFalse(passedThrough.get());
    }

    @Test
    @DisplayName("正确的密钥：放行")
    void correctTokenPasses() throws Exception {
        MockHttpServletResponse resp = run(SECRET, "/api/sync/pull", SECRET);
        assertEquals(200, resp.getStatus());
        assertTrue(passedThrough.get());
    }

    @Test
    @DisplayName("健康检查不需要密钥：配了密钥也能直接探活")
    void healthNeedsNoToken() throws Exception {
        MockHttpServletResponse resp = run(SECRET, "/api/health", null);
        assertEquals(200, resp.getStatus());
        assertTrue(passedThrough.get());
    }
}
