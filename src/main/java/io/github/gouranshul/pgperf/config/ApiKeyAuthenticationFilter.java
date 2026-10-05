package io.github.gouranshul.pgperf.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests carrying {@code Authorization: Bearer <api key>}.
 *
 * <p>The comparison is constant time and length independent: both values are hashed with SHA-256
 * and the digests compared with {@link MessageDigest#isEqual}, so response timing reveals neither
 * matching prefixes nor the key length. Requests without a valid key are left unauthenticated and
 * rejected by the security filter chain.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final byte[] expectedDigest;

    public ApiKeyAuthenticationFilter(String apiKey) {
        this.expectedDigest = sha256(apiKey);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())
                && matches(header.substring(PREFIX.length()).strip())) {
            var authentication = new UsernamePasswordAuthenticationToken("mcp-client", null,
                    List.of(new SimpleGrantedAuthority("ROLE_MCP_CLIENT")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }

    /**
     * Streamable HTTP answers with SSE, which completes in an ASYNC dispatch. Authorization runs
     * again on that dispatch, so the key must be checked again too (the header is still present).
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    boolean matches(String presented) {
        return MessageDigest.isEqual(expectedDigest, sha256(presented));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
