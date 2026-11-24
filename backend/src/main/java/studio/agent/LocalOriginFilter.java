package studio.agent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Do not expose this unauthenticated local application to other browser origins or DNS rebindings.
 */
@Component
public class LocalOriginFilter extends OncePerRequestFilter {
  private static final Set<String> HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    String host = req.getServerName().toLowerCase(Locale.ROOT);
    if (!HOSTS.contains(host)
        || "cross-site".equals(req.getHeader("Sec-Fetch-Site"))
        || !sameOrigin(req, host)) {
      res.setStatus(403);
      res.setContentType("application/json");
      res.getWriter()
          .write(
              "{\"code\":403,\"msg\":\"Local origin required\",\"message\":\"Local origin required\"}");
      return;
    }
    chain.doFilter(req, res);
  }

  private static boolean sameOrigin(HttpServletRequest req, String host) {
    String value = req.getHeader("Origin");
    if (value == null) return true;
    try {
      URI origin = URI.create(value);
      int port =
          origin.getPort() >= 0
              ? origin.getPort()
              : ("https".equals(origin.getScheme()) ? 443 : 80);
      return host.equalsIgnoreCase(origin.getHost())
          && req.getScheme().equals(origin.getScheme())
          && req.getServerPort() == port
          && origin.getUserInfo() == null
          && origin.getRawPath().isEmpty()
          && origin.getFragment() == null
          && origin.getQuery() == null;
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }
}
