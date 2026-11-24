package studio.agent.meeting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import studio.agent.graph.GraphIssue;
import studio.agent.graph.LiveBudget;
import studio.agent.meeting.MeetingGraph.Config;
import studio.agent.meeting.MeetingGraph.Item;
import studio.agent.meeting.MeetingGraph.Problem;
import studio.agent.model.MeetingTaskExtractor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MeetingStudioService {
  public record Draft(long revision, Config config, boolean liveEnabled) {}

  public record Save(long revision, Config config) {}

  public record Start(
      String mode,
      String sampleKey,
      String title,
      String transcript,
      String requestId,
      long revision) {}

  public record Confirm(List<String> selectedIds) {}

  public record Usage(String model, long inputTokens, long outputTokens) {}

  public record Run(
      String runId,
      String mode,
      String sampleKey,
      String title,
      String status,
      long revision,
      Config config,
      List<String> path,
      List<Item> items,
      List<String> selectedIds,
      String createdAt,
      String error,
      Usage usage) {
    Run advance(
        String state,
        List<String> visited,
        List<Item> extracted,
        List<String> selected,
        String message,
        Usage cost) {
      return new Run(
          runId, mode, sampleKey, title, state, revision, config, visited, extracted, selected,
          createdAt, message, cost);
    }
  }

  private record Reservation(Run run, boolean invoke) {}

  private record Stored(String hash, Run run) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final JdbcTemplate db;
  private final TransactionTemplate transaction;
  private final MeetingTaskExtractor extractor;
  private final boolean liveEnabled;
  private final LiveBudget liveBudget;

  public MeetingStudioService(
      JdbcTemplate db,
      TransactionTemplate transaction,
      MeetingTaskExtractor extractor,
      boolean liveEnabled,
      int dailyLimit) {
    this(db, transaction, extractor, liveEnabled, new LiveBudget(db, extractor, dailyLimit));
  }

  @Autowired
  public MeetingStudioService(
      JdbcTemplate db,
      TransactionTemplate transaction,
      MeetingTaskExtractor extractor,
      @Value("${studio.live-enabled:false}") boolean liveEnabled,
      LiveBudget liveBudget) {
    this.db = db;
    this.transaction = transaction;
    this.extractor = extractor;
    this.liveEnabled = liveEnabled;
    this.liveBudget = liveBudget;
  }

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JacksonException e) {
      throw new IllegalStateException("编排数据序列化失败", e);
    }
  }

  private static <T> T read(String value, Class<T> type) {
    try {
      return JSON.readValue(value, type);
    } catch (JacksonException e) {
      throw new IllegalStateException("编排数据格式不兼容", e);
    }
  }

  public Draft draft() {
    return draft(false);
  }

  private Draft draft(boolean lock) {
    return db.queryForObject(
        "SELECT revision, config_json FROM studio_draft WHERE app_key='meeting-notes'"
            + (lock ? " FOR UPDATE" : ""),
        (r, n) ->
            new Draft(
                r.getLong(1),
                read(r.getString(2), Config.class),
                liveEnabled && extractor.available()));
  }

  public Draft save(Save save) {
    Config config = MeetingGraph.checkConfig(save.config());
    return transaction.execute(
        status -> {
          int updated =
              db.update(
                  "UPDATE studio_draft SET config_json=?, revision=revision+1"
                      + " WHERE app_key='meeting-notes' AND revision=?",
                  json(config),
                  save.revision());
          if (updated == 0) throw revisionConflict();
          return draft();
        });
  }

  private static Problem revisionConflict() {
    return new Problem(409, "草稿版本已变化，请重新加载后再试。");
  }

  private static void validate(Start start) {
    if (start.requestId() == null
        || !start.requestId().matches("[a-zA-Z0-9_-]{1,96}")
        || start.revision() < 1) {
      throw new Problem(400, "缺少有效的请求编号或草稿版本。");
    }
    if ("sample".equals(start.mode())) {
      MeetingGraph.sample(start.sampleKey());
      if (start.title() != null || start.transcript() != null)
        throw new Problem(400, "样例运行只接受预设样例。");
    } else if ("live".equals(start.mode())) {
      if (start.sampleKey() != null
          || start.title() == null
          || start.title().isBlank()
          || start.title().length() > 150
          || start.transcript() == null
          || start.transcript().isBlank()
          || start.transcript().length() > 32000) {
        throw new Problem(400, "请输入有效的会议标题和正文（限 32000 字符）。");
      }
    } else throw new Problem(400, "请明确选择样例或模型运行。");
  }

  public Run start(Start request) {
    validate(request);
    // A canonical ASCII key gives H2 and MySQL identical idempotency and payload matching.
    Start start =
        new Start(
            request.mode(),
            request.sampleKey(),
            request.title(),
            request.transcript(),
            request.requestId().toLowerCase(Locale.ROOT),
            request.revision());
    Reservation reserved = transaction.execute(status -> reserve(start));
    if (!reserved.invoke()) return reserved.run();
    Run before = reserved.run();
    Run after;
    try {
      String input = MeetingGraph.modelInput(before.config(), start.title(), start.transcript());
      var response = extractor.extract(input, before.config().prompt());
      after = extracted(before, MeetingGraph.items(response.items()), usage(response));
    } catch (MeetingTaskExtractor.InvalidOutputException ex) {
      after = failed(before, "模型返回的待办格式不符合要求。", usage(ex.usage()));
    } catch (RuntimeException ex) {
      // Provider errors may contain request content or credentials. Persist only a safe
      // classification.
      after = failed(before, "模型调用未完成。本次请求不会自动重发。", null);
    }
    var result = after;
    return transaction.execute(
        status -> {
          boolean updated = update("running", result);
          liveBudget.finish("legacy:" + before.runId());
          return updated ? result : get(before.runId());
        });
  }

  private Reservation reserve(Start start) {
    // A short DB transaction serializes admission with draft edits and concurrent retry requests.
    // The remote model call starts only after the reservation commits and holds no DB locks.
    Draft draft = draft(true);
    expireInterrupted();
    String hash = hash(start);
    List<Stored> prior =
        db.query(
            "SELECT payload_hash, state_json FROM studio_run WHERE request_id=?",
            (row, n) -> new Stored(row.getString(1), read(row.getString(2), Run.class)),
            start.requestId());
    if (!prior.isEmpty()) {
      if (!hash.equals(prior.get(0).hash())) throw new Problem(409, "请求编号已用于不同参数。");
      return new Reservation(prior.get(0).run(), false);
    }
    if (draft.revision() != start.revision()) throw revisionConflict();
    boolean live = "live".equals(start.mode());
    String runId = UUID.randomUUID().toString();
    if (live) {
      if (!liveEnabled || !extractor.available()) throw new Problem(403, "模型试跑未启用或缺少供应商配置。");
      try {
        liveBudget.reserve("legacy:" + runId);
      } catch (GraphIssue e) {
        throw new Problem(e.status(), e.getMessage());
      }
    }
    String title = live ? start.title() : MeetingGraph.sample(start.sampleKey()).title();
    List<String> started =
        draft.config().sanitize()
            ? List.of("start", "sanitize", "extract")
            : List.of("start", "extract");
    Run run =
        new Run(
            runId,
            start.mode(),
            start.sampleKey(),
            title,
            "running",
            draft.revision(),
            draft.config(),
            started,
            List.of(),
            List.of(),
            Instant.now().toString(),
            null,
            null);
    if (!live)
      run = extracted(run, MeetingGraph.preview(draft.config(), start.sampleKey()).items(), null);
    db.update(
        "INSERT INTO studio_run(run_id,request_id,payload_hash,run_mode,status,state_json)"
            + " VALUES (?,?,?,?,?,?)",
        run.runId(),
        start.requestId(),
        hash,
        start.mode(),
        run.status(),
        json(run));
    return new Reservation(run, live);
  }

  private static String hash(Start start) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(json(start).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void expireInterrupted() {
    // A crashed process must never replay a potentially billable request. Retain its receipt.
    List<Run> stale =
        db.query(
            "SELECT state_json FROM studio_run WHERE status='running' AND created_at<?",
            (row, n) -> read(row.getString(1), Run.class),
            Timestamp.from(Instant.now().minus(Duration.ofHours(1))));
    for (Run run : stale) update("running", failed(run, "运行已中断。本次请求不会自动重发。", null));
  }

  private static Run failed(Run run, String error, Usage usage) {
    return run.advance("failed", run.path(), List.of(), List.of(), error, usage);
  }

  private static Usage usage(MeetingTaskExtractor.Result result) {
    return new Usage(result.model(), result.inputTokens(), result.outputTokens());
  }

  private static Run extracted(Run run, List<Item> items, Usage usage) {
    boolean waiting = items.size() >= run.config().threshold();
    return run.advance(
        waiting ? "waiting" : "completed",
        MeetingGraph.path(run.config(), waiting),
        items,
        List.of(),
        null,
        usage);
  }

  private boolean update(String expected, Run next) {
    return db.update(
            "UPDATE studio_run SET status=?, state_json=? WHERE run_id=? AND status=?",
            next.status(),
            json(next),
            next.runId(),
            expected)
        == 1;
  }

  public Run get(String id) {
    expireInterrupted();
    return find(id, false);
  }

  private Run find(String id, boolean lock) {
    List<Run> rows =
        db.query(
            "SELECT state_json FROM studio_run WHERE run_id=?" + (lock ? " FOR UPDATE" : ""),
            (row, n) -> read(row.getString(1), Run.class),
            id);
    if (rows.isEmpty()) throw new Problem(404, "找不到运行记录。");
    return rows.get(0);
  }

  public List<Run> list() {
    expireInterrupted();
    return db.query(
        "SELECT state_json FROM studio_run ORDER BY created_at DESC,run_id DESC LIMIT 50",
        (row, n) -> read(row.getString(1), Run.class));
  }

  public Run confirm(String id, Confirm confirmation) {
    return transaction.execute(
        status -> {
          Run run = find(id, true);
          List<String> requested = confirmation.selectedIds();
          if (requested == null || requested.size() > run.items().size())
            throw new Problem(400, "请选择有效的待办。");
          var selection = new HashSet<>(requested);
          List<String> ids =
              run.items().stream().map(Item::id).filter(selection::contains).toList();
          if (ids.size() != requested.size()) throw new Problem(400, "待办编号重复或无效。");
          if ("completed".equals(run.status())
              && run.path().contains("confirm")
              && run.selectedIds().equals(ids)) return run;
          if (!"waiting".equals(run.status())) throw new Problem(409, "当前运行已经结束或尚未进入确认节点。");
          Run next =
              run.advance(
                  "completed",
                  MeetingGraph.confirmedPath(run.path(), !ids.isEmpty()),
                  run.items(),
                  ids,
                  null,
                  run.usage());
          if (!update("waiting", next)) throw new Problem(409, "运行状态已变化。");
          return next;
        });
  }
}
