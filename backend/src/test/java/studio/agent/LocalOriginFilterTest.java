package studio.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalOriginFilterTest {
  private final LocalOriginFilter filter = new LocalOriginFilter();

  @Test
  void rejectsForeignOriginsAndReboundHostNames() throws Exception {
    for (String[] input :
        new String[][] {
          {"127.0.0.1", "https://untrusted.example"},
          {"foreign.example", "http://foreign.example:5173"},
          {"localhost", "http://localhost:9090"},
          {"127.0.0.1", "null"}
        }) {
      var request = new MockHttpServletRequest("POST", "/api/studio/meeting-notes/runs");
      request.setServerName(input[0]);
      request.setServerPort(5173);
      request.addHeader("Origin", input[1]);
      var response = new MockHttpServletResponse();
      var chain = new MockFilterChain();
      filter.doFilter(request, response, chain);
      assertEquals(403, response.getStatus());
      assertNull(chain.getRequest());
    }
  }

  @Test
  void allowsSameOriginDevProxyAndLoopbackCli() throws Exception {
    for (String origin : new String[] {"http://127.0.0.1:5173", null}) {
      var request = new MockHttpServletRequest("POST", "/api/studio/meeting-notes/preview");
      request.setServerName("127.0.0.1");
      request.setServerPort(5173);
      if (origin != null) request.addHeader("Origin", origin);
      var chain = new MockFilterChain();
      filter.doFilter(request, new MockHttpServletResponse(), chain);
      assertSame(request, chain.getRequest());
    }
  }
}
