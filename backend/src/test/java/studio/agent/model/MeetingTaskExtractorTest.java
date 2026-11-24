package studio.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MeetingTaskExtractorTest {
  private final JsonMapper json = JsonMapper.builder().build();
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicReference<JsonNode> posted = new AtomicReference<>();
  private HttpServer server;
  private MockEnvironment config;

  @BeforeEach
  void startLocalProvider() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    config =
        new MockEnvironment()
            .withProperty("STUDIO_LIVE_ENABLED", "true")
            .withProperty("STUDIO_MODEL_API_KEY", "fake-test-key")
            .withProperty("STUDIO_MODEL_NAME", "configured-model")
            .withProperty(
                "STUDIO_MODEL_BASE_URL",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
  }

  @AfterEach
  void stopLocalProvider() {
    server.stop(0);
  }

  @Test
  void defaultOrMissingCredentialsKeepsModelsOff() {
    for (var env :
        List.of(
            new MockEnvironment(),
            config.withProperty("STUDIO_LIVE_ENABLED", "false"),
            new MockEnvironment()
                .withProperty("STUDIO_LIVE_ENABLED", "true")
                .withProperty("STUDIO_MODEL_NAME", "m"))) {
      var extractor = new MeetingTaskExtractor(env);
      assertThat(extractor.available()).isFalse();
      assertThatThrownBy(() -> extractor.extract("meeting", ""))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(requests.get()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"max_completion_tokens", "max_tokens"})
  void actualModelSendsBoundedRequestAndReadsUsage(String field) {
    chat("{\"items\":[\" Friday review \"]}");
    var extractor =
        new MeetingTaskExtractor(config.withProperty("STUDIO_MODEL_TOKEN_FIELD", field));
    assertThat(extractor.available()).isTrue();
    var result = extractor.extract("Private transcript", "Retain dates");
    assertThat(result)
        .isEqualTo(
            new MeetingTaskExtractor.Result(List.of("Friday review"), "reported-model", 13, 6));
    assertThat(requests.get()).isOne();
    var request = posted.get();
    assertThat(request.path(field).asInt()).isEqualTo(2048);
    assertThat(request.has(field.equals("max_tokens") ? "max_completion_tokens" : "max_tokens"))
        .isFalse();
    assertThat(request.path("model").asString()).isEqualTo("configured-model");
    assertThat(request.at("/messages/0/content").asString())
        .contains("Retain dates", "items")
        .doesNotContain("Private transcript");
    assertThat(request.at("/messages/1/content").asString()).isEqualTo("Private transcript");
    assertThat(request.toString()).doesNotContain("fake-test-key");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "private upstream content",
        "{\"items\":[1]}",
        "{\"items\":[null]}",
        "{\"items\":[] , \"extra\":1}",
        "{}",
        "{\"items\":[]} {\"items\":[]}"
      })
  void malformedAnswerIsNotRetriedAndKeepsOnlyUsage(String answer) {
    chat(answer);
    assertThatThrownBy(() -> new MeetingTaskExtractor(config).extract("private meeting", ""))
        .isInstanceOfSatisfying(
            MeetingTaskExtractor.InvalidOutputException.class,
            ex -> {
              assertThat(ex.getMessage()).doesNotContain("private", "fake-test-key");
              assertThat(ex.getCause()).isNull();
              assertThat(ex.usage())
                  .isEqualTo(new MeetingTaskExtractor.Result(List.of(), "reported-model", 13, 6));
            });
    assertThat(requests.get()).isOne();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 429, 500, 503})
  void connectionLossAndRetryableStatusesDoNotResubmit(int status) {
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          if (status == 0) exchange.close();
          else send(exchange, status, "{\"error\":{\"message\":\"private upstream content\"}}");
        });
    var extractor = new MeetingTaskExtractor(config);
    assertThatThrownBy(() -> extractor.extract("private meeting", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasNoCause()
        .hasMessageNotContaining("private")
        .hasMessageNotContaining("fake-test-key");
    assertThat(requests.get()).isOne();
  }

  @Test
  void rejectsTooManyItemsAndOversizeTitles() {
    var extractor = new MeetingTaskExtractor(config);
    for (var titles :
        List.of(java.util.Collections.nCopies(51, "task"), List.of("x".repeat(501)))) {
      chat(json.writeValueAsString(Map.of("items", titles)));
      assertThatThrownBy(() -> extractor.extract("meeting", ""))
          .isInstanceOf(MeetingTaskExtractor.InvalidOutputException.class);
      server.removeContext("/v1/chat/completions");
    }
    assertThat(requests.get()).isEqualTo(2);
  }

  @Test
  void emptyTaskListIsASuccessfulExtraction() {
    chat("{\"items\":[]}");
    assertThat(new MeetingTaskExtractor(config).extract("meeting", "").items()).isEmpty();
    assertThat(requests.get()).isOne();
  }

  @Test
  void rejectsUnboundedInputBeforeSending() {
    var extractor = new MeetingTaskExtractor(config);
    assertThatThrownBy(() -> extractor.extract("x".repeat(32_001), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> extractor.extract("hi", "z".repeat(2_001)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(requests.get()).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://external.example/v1",
        "https://user:private@external.example/v1",
        "https://external.example/v1?secret=private",
        "malformed private"
      })
  void rejectsInsecureOrCredentialBearingUrls(String uri) {
    assertThatThrownBy(
            () -> new MeetingTaskExtractor(config.withProperty("STUDIO_MODEL_BASE_URL", uri)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("private")
        .hasNoCause();
  }

  @Test
  void assignedSchemaKeepsExplicitOwnersAndRepresentsMissingUsageAsUnknown() {
    chat(
        "{\"items\":[{\"title\":\"Send report\",\"assignee\":\"Alice\"},{\"title\":\"Plan"
            + " review\",\"assignee\":\"\"}]}",
        false);
    var result = new MeetingTaskExtractor(config).extractAssigned("Discuss tasks", "");
    assertThat(result.items())
        .extracting(MeetingTaskExtractor.AssignedTask::assignee)
        .containsExactly("Alice", "");
    assertThat(result.usage().inputTokens()).isEqualTo(-1);
    assertThat(result.usage().outputTokens()).isEqualTo(-1);
    assertThat(requests.get()).isOne();
  }

  private void chat(String content) {
    chat(content, true);
  }

  private void chat(String content, boolean hasUsage) {
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          posted.set(json.readTree(exchange.getRequestBody().readAllBytes()));
          if (!"Bearer fake-test-key"
              .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            send(exchange, 401, "{}");
            return;
          }
          var payload =
              new java.util.LinkedHashMap<>(
                  Map.<String, Object>of(
                      "id",
                      "test",
                      "model",
                      "reported-model",
                      "object",
                      "chat.completion",
                      "created",
                      1,
                      "choices",
                      List.of(
                          Map.of(
                              "index",
                              0,
                              "finish_reason",
                              "stop",
                              "message",
                              Map.of("role", "assistant", "content", content)))));
          if (hasUsage)
            payload.put(
                "usage", Map.of("prompt_tokens", 13, "completion_tokens", 6, "total_tokens", 19));
          send(exchange, 200, json.writeValueAsString(payload));
        });
  }

  private static void send(HttpExchange exchange, int status, String data) throws IOException {
    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
