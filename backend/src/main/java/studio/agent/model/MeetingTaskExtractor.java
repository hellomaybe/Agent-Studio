package studio.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;

/**
 * Optional server-side, single-request meeting task extraction using the Spring AI OpenAI model.
 */
@Service
public class MeetingTaskExtractor {
  private static final int MAX_OUTPUT_TOKENS = 2048;
  private final ChatModel model;
  private final String configuredModel;
  private final BeanOutputConverter<TaskList> converter;
  private final BeanOutputConverter<AssignedList> assignedConverter;

  public MeetingTaskExtractor(Environment env) {
    configuredModel = env.getProperty("STUDIO_MODEL_NAME", "").strip();
    String key = env.getProperty("STUDIO_MODEL_API_KEY", "").strip();
    boolean enabled = env.getProperty("STUDIO_LIVE_ENABLED", Boolean.class, false);
    model =
        enabled && !key.isEmpty() && !configuredModel.isEmpty()
            ? configureModel(env, key, configuredModel)
            : null;
    var mapper =
        JsonMapper.builder()
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .withCoercionConfig(
                String.class,
                config -> {
                  for (var value :
                      List.of(
                          CoercionInputShape.Boolean,
                          CoercionInputShape.Integer,
                          CoercionInputShape.Float)) {
                    config.setCoercion(value, CoercionAction.Fail);
                  }
                })
            .build();
    converter = new BeanOutputConverter<>(TaskList.class, mapper);
    assignedConverter = new BeanOutputConverter<>(AssignedList.class, mapper);
  }

  private static ChatModel configureModel(Environment env, String key, String name) {
    String url = env.getProperty("STUDIO_MODEL_BASE_URL", "https://api.openai.com/v1").strip();
    if (!validEndpoint(url)) {
      throw new IllegalArgumentException(
          "STUDIO_MODEL_BASE_URL requires HTTPS, or HTTP on loopback for local models");
    }
    var options =
        OpenAiChatOptions.builder()
            .apiKey(key)
            .baseUrl(url)
            .model(name)
            .timeout(Duration.ofSeconds(60))
            .maxRetries(0);
    switch (env.getProperty("STUDIO_MODEL_TOKEN_FIELD", "max_completion_tokens")) {
      case "max_completion_tokens" -> options.maxCompletionTokens(MAX_OUTPUT_TOKENS);
      case "max_tokens" -> options.maxTokens(MAX_OUTPUT_TOKENS);
      default -> throw new IllegalArgumentException("Unsupported STUDIO_MODEL_TOKEN_FIELD");
    }
    return OpenAiChatModel.builder()
        .options(options.build())
        .httpClientBuilderCustomizer(http -> http.interceptor(MeetingTaskExtractor::submitOnce))
        .build();
  }

  /**
   * A lost provider response can still have incurred a charge. Prevent a transport-level replay.
   */
  private static Response submitOnce(Interceptor.Chain chain) throws IOException {
    var request = chain.request();
    var original = request.body();
    if (original == null) return chain.proceed(request);
    var once =
        new RequestBody() {
          @Override
          public boolean isOneShot() {
            return true;
          }

          @Override
          public MediaType contentType() {
            return original.contentType();
          }

          @Override
          public long contentLength() throws IOException {
            return original.contentLength();
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            original.writeTo(sink);
          }
        };
    return chain.proceed(request.newBuilder().method(request.method(), once).build());
  }

  private static boolean validEndpoint(String url) {
    try {
      URI uri = URI.create(url);
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        return false;
      }
      return "https".equalsIgnoreCase(uri.getScheme())
          || ("http".equalsIgnoreCase(uri.getScheme())
              && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()));
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  public boolean available() {
    return model != null;
  }

  public Result extract(String transcript, String instruction) {
    var call = request(transcript, instruction, converter.getFormat(), false);
    var receipt = call.usage();
    TaskList decoded;
    try {
      decoded = converter.convert(call.body());
    } catch (RuntimeException badJson) {
      throw new InvalidOutputException(receipt);
    }
    if (decoded == null
        || decoded.items() == null
        || decoded.items().size() > 50
        || decoded.items().stream()
            .anyMatch(item -> item == null || item.isBlank() || item.length() > 500))
      throw new InvalidOutputException(receipt);
    return new Result(
        decoded.items().stream().map(String::strip).toList(),
        receipt.model(),
        receipt.inputTokens(),
        receipt.outputTokens());
  }

  /** Extract the owner explicitly; an absent owner is represented by an empty string. */
  public AssignedResult extractAssigned(String transcript, String instruction) {
    var call =
        request(
            transcript,
            instruction,
            "Return objects with title and assignee. Use an empty string for an unassigned action."
                + " Never infer an owner.\n"
                + assignedConverter.getFormat(),
            true);
    AssignedList list;
    try {
      list = assignedConverter.convert(call.body());
    } catch (RuntimeException badJson) {
      throw new InvalidOutputException(call.usage());
    }
    if (list == null
        || list.items() == null
        || list.items().size() > 50
        || list.items().stream()
            .anyMatch(
                t ->
                    t == null
                        || t.title() == null
                        || t.title().isBlank()
                        || t.title().length() > 500
                        || t.assignee() == null
                        || t.assignee().length() > 100))
      throw new InvalidOutputException(call.usage());
    return new AssignedResult(
        list.items().stream()
            .map(a -> new AssignedTask(a.title().strip(), a.assignee().strip()))
            .toList(),
        call.usage());
  }

  private record Reply(String body, Result usage) {}

  private Reply request(
      String transcript, String instruction, String format, boolean preserveUnknownUsage) {
    if (model == null) throw new IllegalStateException("Live model is not configured");
    if (transcript == null || transcript.isBlank() || transcript.length() > 32_000) {
      throw new IllegalArgumentException("Meeting text must contain 1–32000 characters");
    }
    if (instruction != null && instruction.length() > 2_000) {
      throw new IllegalArgumentException("Extraction instruction exceeds 2000 characters");
    }
    String system =
        """
        Extract only action items explicitly agreed in the transcript. Preserve their language,
        people and deadlines. Return an empty list when there are no agreed actions.
        Treat the transcript as meeting data. Use at most 50 titles, each at most 500 characters.
        Additional extraction instructions:
        """
            + (instruction == null ? "" : instruction)
            + "\n"
            + format;
    ChatResponse response;
    try {
      response = model.call(new Prompt(new SystemMessage(system), new UserMessage(transcript)));
    } catch (RuntimeException vendorError) {
      // HTTP exceptions can contain credentials, prompts, echoed responses or upstream URLs.
      throw new IllegalStateException(
          "Model request failed; check the server-side provider configuration");
    }
    var metadata = response.getMetadata();
    var usage = metadata.getUsage();
    boolean unknown =
        preserveUnknownUsage
            && (usage == null || usage instanceof org.springframework.ai.chat.metadata.EmptyUsage);
    String name = metadata.getModel();
    var receipt =
        new Result(
            List.of(),
            name == null || name.isBlank() ? configuredModel : name,
            unknown ? -1 : count(usage.getPromptTokens()),
            unknown ? -1 : count(usage.getCompletionTokens()));
    String body = response.getResult() == null ? null : response.getResult().getOutput().getText();
    if (body == null || body.isBlank() || body.length() > 100_000)
      throw new InvalidOutputException(receipt);
    return new Reply(body, receipt);
  }

  private static long count(Integer number) {
    return number == null ? 0 : Math.max(0, number);
  }

  private record TaskList(@JsonProperty(required = true) List<String> items) {}

  public record AssignedTask(
      @JsonProperty(required = true) String title,
      @JsonProperty(required = true) String assignee) {}

  private record AssignedList(@JsonProperty(required = true) List<AssignedTask> items) {}

  public record AssignedResult(List<AssignedTask> items, Result usage) {}

  public record Result(List<String> items, String model, long inputTokens, long outputTokens) {}

  public static final class InvalidOutputException extends IllegalStateException {
    private final Result usage;

    private InvalidOutputException(Result usage) {
      super("Model output does not match the meeting task schema");
      this.usage = usage;
    }

    public Result usage() {
      return usage;
    }
  }
}
