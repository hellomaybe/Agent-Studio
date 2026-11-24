package studio.agent.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import studio.agent.graph.Graph;
import studio.agent.graph.GraphCatalog;
import studio.agent.graph.GraphIssue;
import studio.agent.graph.LiveBudget;
import studio.agent.graph.NodeHandler;
import studio.agent.graph.NodeRegistry;
import studio.agent.model.MeetingTaskExtractor;
import tools.jackson.databind.json.JsonMapper;

/** Serial graph activations. External calls run outside database transactions. */
@Service
public class WorkflowRunService {
  public record Start(
      Long versionId,
      String requestId,
      String mode,
      Map<String, Object> input,
      Boolean persistInput,
      String pauseAfterNode) {}

  public record Signal(String signalId, String waitId, Map<String, Object> values) {}

  public record Reinput(String requestId, Map<String, Object> input, Boolean persistInput) {}

  public record Review(String signalId, String decision, String receiptRef) {}

  public record Field(String key, String type, String label) {}

  public record WaitPrompt(
      String waitId,
      String nodeId,
      String prompt,
      List<Map<String, Object>> items,
      List<Field> fields,
      String since) {}

  public record Event(
      long seq,
      String type,
      String nodeId,
      String nodeType,
      String status,
      String occurredAt,
      Long elapsedMs,
      String errorCode,
      Map<String, Object> usage,
      Integer activationNo,
      Integer attemptNo,
      String port,
      String nextNodeId,
      String operationKey,
      List<String> inputFields,
      List<String> outputFields,
      String relatedRunId,
      String receiptFingerprint,
      Long waitMs) {}

  public record HistoryEntry(
      String runId,
      String appId,
      String title,
      long versionId,
      String mode,
      String status,
      String createdAt) {}

  public record EventPage(List<Event> events, long nextCursor, boolean hasMore) {}

  public record DebugRequest(String nodeId, Boolean includeContent) {}

  public record DebugInput(String nodeId, long versionId, Map<String, Object> inputs) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  private record LegacyHistory(long version, String mode) {}

  public record View(
      String runId,
      String appId,
      long versionId,
      String mode,
      String status,
      String currentNodeId,
      boolean pauseRequested,
      List<String> inputKeys,
      Map<String, Object> output,
      @com.fasterxml.jackson.annotation.JsonProperty("wait") WaitPrompt waitPrompt,
      List<String> path,
      List<Event> events,
      List<Map<String, Object>> effects,
      Map<String, String> capabilityVersions,
      String createdAt,
      String updatedAt,
      String expiresAt,
      String errorCode,
      Graph graph,
      boolean canRetry,
      boolean canReinput,
      boolean reviewed,
      String receiptFingerprint,
      String parentRunId,
      long eventCount,
      boolean eventsTruncated) {}

  /** Internal checkpoint. Only the view projection is returned by the REST API. */
  public static final class Checkpoint {
    public int schemaVersion = 1;
    public String id,
        app,
        mode,
        status,
        node,
        createdAt,
        updatedAt,
        expiresAt,
        startedAt,
        error,
        pauseAfter;
    public long version, seq;
    public int activation = 1, attempt;
    public boolean pause, purged;
    public boolean reviewed;
    public String receiptFingerprint, parentRunId;
    public Graph graph;
    public Map<String, String> capabilities = new LinkedHashMap<>();
    public Map<String, Boolean> replayPolicies = new LinkedHashMap<>();
    public Map<String, Boolean> liveModelPolicies = new LinkedHashMap<>();
    public Map<String, List<String>> declaredInputs = new LinkedHashMap<>(),
        declaredOutputs = new LinkedHashMap<>();
    public Map<String, Object> input = Map.of(), output = Map.of();
    public Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
    public List<String> path = new ArrayList<>(), approved;
    public WaitPrompt waiting;
  }

  static final class Fault extends RuntimeException {
    final int status;

    Fault(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  private record Stored(String hash, Checkpoint state) {}

  private record Activation(Checkpoint state, Graph.Node node) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Duration LEASE = Duration.ofMinutes(3);
  private static final int RECENT_EVENTS = 100;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final TransactionTemplate snapshot;
  private final GraphCatalog catalog;
  private final NodeRegistry registry;
  private final LiveBudget budget;

  public WorkflowRunService(
      JdbcTemplate db,
      TransactionTemplate tx,
      GraphCatalog catalog,
      NodeRegistry registry,
      LiveBudget budget) {
    this.db = db;
    this.tx = tx;
    this.snapshot = new TransactionTemplate(tx.getTransactionManager());
    this.snapshot.setReadOnly(true);
    this.snapshot.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.catalog = catalog;
    this.registry = registry;
    this.budget = budget;
  }

  static String json(Object value) {
    return JSON.writeValueAsString(value);
  }

  private static String key(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,96}")) throw new Fault(400, "缺少有效的幂等编号。");
    return value.toLowerCase(Locale.ROOT);
  }

  private static String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(json(value).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public View start(String app, Start request) {
    return start(app, request, null);
  }

  public View reinput(String app, String parent, Reinput input) {
    return start(
        app,
        new Start(null, input.requestId(), "live", input.input(), input.persistInput(), null),
        parent);
  }

  private View start(String app, Start request, String parentId) {
    String requestId = key(request.requestId());
    String mode = request.mode() == null ? "sample" : request.mode();
    if (!Set.of("sample", "live").contains(mode)) throw new Fault(400, "不支持此运行模式。");
    if ("live".equals(mode) && !Boolean.TRUE.equals(request.persistInput()))
      throw new Fault(400, "模型运行需明确同意在本机保存运行内容 24 小时。");
    if (request.input() == null
        || request.input().size() != 1
        || !(request.input().get("text") instanceof String text)
        || text.isBlank()
        || text.length() > 32000) throw new Fault(400, "需要 1 至 32000 字符的 text 输入。");
    var signature =
        hash(
            Arrays.asList(
                mode,
                parentId,
                request.versionId(),
                text,
                request.pauseAfterNode(),
                Boolean.TRUE.equals(request.persistInput())));
    try {
      String id =
          tx.execute(
              s -> {
                List<Stored> existing = byRequest(app, requestId);
                if (!existing.isEmpty()) return same(existing.get(0), signature);
                Checkpoint parent = null;
                if (parentId != null) {
                  parent = load(app, parentId, true);
                  expire(parent);
                  if (!canReinput(parent)) throw new Fault(409, "请先结束或核对此运行；重新提交将创建另一条运行。");
                }
                var version =
                    catalog.resolve(
                        app, parent == null ? request.versionId() : Long.valueOf(parent.version));
                Graph graph = version.graph();
                if (request.pauseAfterNode() != null
                    && graph.nodes().stream()
                        .noneMatch(n -> n.id().equals(request.pauseAfterNode())))
                  throw new Fault(400, "断点节点不存在。");
                Checkpoint c = new Checkpoint();
                c.id = UUID.randomUUID().toString();
                c.app = app;
                c.version = version.version();
                c.graph = graph;
                c.mode = mode;
                c.parentRunId = parentId;
                c.capabilities.putAll(version.capabilities());
                for (var d : registry.descriptors())
                  if (c.capabilities.containsKey(d.type())) {
                    c.declaredInputs.put(d.type(), d.inputs().keySet().stream().sorted().toList());
                    c.declaredOutputs.put(
                        d.type(), d.outputs().keySet().stream().sorted().toList());
                  }
                for (var n : graph.nodes()) {
                  c.replayPolicies.put(n.type(), registry.replaySafe(n.type(), mode));
                  c.liveModelPolicies.put(n.type(), registry.usesLiveModel(n.type()));
                }
                c.node =
                    graph.nodes().stream()
                        .filter(n -> "start".equals(n.type()))
                        .findFirst()
                        .orElseThrow()
                        .id();
                var now = Instant.now();
                c.createdAt = now.toString();
                c.updatedAt = c.createdAt;
                c.input = Map.of("text", text);
                c.status = "queued";
                c.expiresAt =
                    "live".equals(mode) ? now.plus(Duration.ofHours(24)).toString() : null;
                c.pauseAfter = request.pauseAfterNode();
                db.update(
                    "INSERT INTO workflow_run(run_id,app_key,request_id,payload_hash,status,created_at,expires_at,state_json) VALUES (?,?,?,?,?,?,?,?)",
                    c.id,
                    app,
                    requestId,
                    signature,
                    c.status,
                    Timestamp.from(now),
                    timestamp(c.expiresAt),
                    json(c));
                event(c, "run.accepted", null, null, null, null, null);
                if (parent != null) {
                  append(parent, "run.reinput", null, null, null, null, null, null, null, c.id);
                  save(parent);
                }
                save(c);
                return c.id;
              });
      return get(app, id);
    } catch (DuplicateKeyException race) {
      List<Stored> winner = byRequest(app, requestId);
      if (winner.isEmpty()) throw race;
      return get(app, same(winner.get(0), signature));
    }
  }

  private List<Stored> byRequest(String app, String key) {
    return db.query(
        "SELECT payload_hash,state_json FROM workflow_run WHERE app_key=? AND request_id=?",
        (r, i) -> new Stored(r.getString(1), JSON.readValue(r.getString(2), Checkpoint.class)),
        app,
        key);
  }

  private String same(Stored old, String signature) {
    if (!old.hash().equals(signature)) throw new Fault(409, "请求编号已用于不同参数。");
    return old.state().id;
  }

  private Checkpoint load(String app, String id, boolean lock) {
    List<Checkpoint> found =
        db.query(
            "SELECT state_json FROM workflow_run WHERE app_key=? AND run_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (r, i) -> JSON.readValue(r.getString(1), Checkpoint.class),
            app,
            id);
    if (found.isEmpty()) throw new Fault(404, "找不到运行记录。");
    return found.get(0);
  }

  private static Timestamp timestamp(String value) {
    return value == null ? null : Timestamp.from(Instant.parse(value));
  }

  private void save(Checkpoint c) {
    c.updatedAt = Instant.now().toString();
    Timestamp lease =
        "running".equals(c.status) ? Timestamp.from(Instant.parse(c.startedAt).plus(LEASE)) : null;
    db.update(
        "UPDATE workflow_run SET status=?,state_json=?,lease_until=?,expires_at=?,graph_version=?,run_mode=? WHERE run_id=?",
        c.status,
        json(c),
        lease,
        c.purged ? null : timestamp(c.expiresAt),
        c.version,
        c.mode,
        c.id);
  }

  private void event(
      Checkpoint c,
      String type,
      String nodeId,
      String nodeType,
      Long elapsed,
      String error,
      Map<String, Object> usage) {
    append(c, type, nodeId, nodeType, elapsed, error, usage, null, null, null);
  }

  private void append(
      Checkpoint c,
      String type,
      String nodeId,
      String nodeType,
      Long elapsed,
      String error,
      Map<String, Object> usage,
      String port,
      String nextNodeId,
      String relatedRunId) {
    append(c, type, nodeId, nodeType, elapsed, error, usage, port, nextNodeId, relatedRunId, null);
  }

  private void append(
      Checkpoint c,
      String type,
      String nodeId,
      String nodeType,
      Long elapsed,
      String error,
      Map<String, Object> usage,
      String port,
      String nextNodeId,
      String relatedRunId,
      String waitStarted) {
    Instant at = "node.waiting".equals(type) ? Instant.parse(c.waiting.since()) : Instant.now();
    Long waitMs = null;
    if (waitStarted != null) {
      long measured = Duration.between(Instant.parse(waitStarted), at).toMillis();
      if (measured >= 0) waitMs = measured;
    }
    var event =
        new Event(
            ++c.seq,
            type,
            nodeId,
            nodeType,
            "node.completed".equals(type) ? "completed" : c.status,
            at.toString(),
            elapsed,
            error,
            usage,
            nodeId == null ? null : c.activation,
            nodeId == null || c.attempt == 0 ? null : c.attempt,
            port,
            nextNodeId,
            nodeId == null ? null : c.id + ":" + c.activation,
            nodeType == null ? List.of() : c.declaredInputs.getOrDefault(nodeType, List.of()),
            nodeType == null ? List.of() : c.declaredOutputs.getOrDefault(nodeType, List.of()),
            relatedRunId,
            type.startsWith("signal.reconcile.") ? c.receiptFingerprint : null,
            waitMs);
    db.update(
        "INSERT INTO workflow_event(run_id,seq,event_json) VALUES(?,?,?)",
        c.id,
        c.seq,
        json(event));
  }

  public View get(String app, String id) {
    View result =
        snapshot.execute(
            transaction -> {
              var state = load(app, id, false);
              return due(state) ? null : view(state);
            });
    if (result != null) return result;
    return tx.execute(
        transaction -> {
          var state = load(app, id, true);
          expire(state);
          return view(state);
        });
  }

  public List<HistoryEntry> list(String app) {
    // Older, inactive checkpoints remain readable. New and updated runs need no payload read.
    return db.query(
        """
        SELECT r.run_id,r.app_key,a.display_name,r.graph_version,r.run_mode,r.status,r.created_at,
            CASE WHEN r.graph_version IS NULL THEN r.state_json ELSE NULL END AS legacy
        FROM workflow_run r LEFT JOIN studio_graph_app a ON a.app_id=r.app_key
        WHERE r.app_key=? ORDER BY r.created_at DESC,r.run_id DESC LIMIT 50
        """,
        (r, i) -> {
          Long version = r.getObject(4, Long.class);
          String mode = r.getString(5);
          if (version == null) {
            var old = JSON.readValue(r.getCharacterStream(8), LegacyHistory.class);
            version = old.version();
            mode = old.mode();
          }
          String title = r.getString(3);
          return new HistoryEntry(
              r.getString(1),
              r.getString(2),
              title == null ? app : title,
              version,
              mode,
              r.getString(6),
              r.getTimestamp(7).toInstant().toString());
        },
        app);
  }

  public EventPage events(String app, String id, long after, int limit) {
    if (after < 0 || limit < 1 || limit > 200) throw new Fault(400, "事件页大小须为 1–200，游标不能为负数。");
    if (db.queryForList(
            "SELECT 1 FROM workflow_run WHERE app_key=? AND run_id=?", Integer.class, app, id)
        .isEmpty()) throw new Fault(404, "找不到运行记录。");
    var rows =
        db.query(
            "SELECT event_json FROM workflow_event WHERE run_id=? AND seq>? ORDER BY seq LIMIT ?",
            (r, i) -> JSON.readValue(r.getString(1), Event.class),
            id,
            after,
            limit + 1);
    boolean more = rows.size() > limit;
    List<Event> page = more ? rows.subList(0, limit) : rows;
    return new EventPage(page, page.isEmpty() ? after : page.get(page.size() - 1).seq(), more);
  }

  /**
   * Acyclic published graphs bind each input to one immutable upstream output. No node is called.
   */
  public DebugInput debugInput(String app, String id, DebugRequest request) {
    if (!Boolean.TRUE.equals(request.includeContent())) throw new Fault(400, "请明确选择读取这次运行的输入内容。");
    if (request.nodeId() == null || request.nodeId().isBlank() || request.nodeId().length() > 128)
      throw new Fault(400, "节点编号无效。");
    return snapshot.execute(
        t -> {
          var c = load(app, id, false);
          if (c.purged || due(c) || c.input.isEmpty()) throw new Fault(410, "运行输入已过期或不可用。");
          var n =
              c.graph.nodes().stream()
                  .filter(node -> node.id().equals(request.nodeId()))
                  .findFirst()
                  .orElseThrow(() -> new Fault(404, "此版本中没有该节点。"));
          if (!c.path.contains(n.id())
              || db.queryForList(
                      "SELECT 1 FROM workflow_step_attempt WHERE run_id=? AND node_id=? AND finished_at IS NOT NULL AND elapsed_ms IS NOT NULL LIMIT 1",
                      Integer.class,
                      id,
                      n.id())
                  .isEmpty()) throw new Fault(409, "此节点没有可核对的已执行尝试。");
          try {
            Map<String, Object> inputs =
                "start".equals(n.type()) ? c.input : registry.bind(n, c.outputs);
            return new DebugInput(n.id(), c.version, inputs);
          } catch (GraphIssue missing) {
            throw new Fault(409, "此节点所需的历史输入不可完整还原。");
          }
        });
  }

  @SuppressWarnings("unchecked")
  private View view(Checkpoint c) {
    List<Event> events =
        db.query(
            "SELECT event_json FROM workflow_event WHERE run_id=? AND seq<=? ORDER BY seq DESC LIMIT ?",
            (r, i) -> JSON.readValue(r.getString(1), Event.class),
            c.id,
            c.seq,
            RECENT_EVENTS);
    Collections.reverse(events);
    List<Map<String, Object>> effects =
        db.query(
            "SELECT activation_no,position_no,effect_json FROM workflow_local_effect WHERE run_id=? ORDER BY activation_no,position_no",
            (r, i) -> {
              Map<String, Object> effect =
                  new LinkedHashMap<>(JSON.readValue(r.getString(3), Map.class));
              String operation = c.id + ":" + r.getInt(1);
              effect.put("operationKey", operation);
              effect.put("effectId", operation + ":" + r.getInt(2));
              return effect;
            },
            c.id);
    return new View(
        c.id,
        c.app,
        c.version,
        c.mode,
        c.status,
        c.node,
        c.pause,
        List.copyOf(c.input.keySet()),
        c.output,
        c.waiting,
        c.path,
        events,
        effects,
        c.capabilities,
        c.createdAt,
        c.updatedAt,
        c.expiresAt,
        c.error,
        c.graph,
        canRetry(c),
        canReinput(c),
        c.reviewed,
        c.receiptFingerprint,
        c.parentRunId,
        c.seq,
        c.seq > events.size());
  }

  private boolean canRetry(Checkpoint c) {
    return !c.purged
        && "failed".equals(c.status)
        && Set.of("node_failed", "node_validation_failed", "capability_version_unavailable")
            .contains(c.error)
        && Boolean.TRUE.equals(c.replayPolicies.get(current(c).type()));
  }

  private boolean canReinput(Checkpoint c) {
    return "live".equals(c.mode) && Set.of("expired", "failed", "terminated").contains(c.status);
  }

  private boolean due(Checkpoint c) {
    return !c.purged && c.expiresAt != null && !Instant.parse(c.expiresAt).isAfter(Instant.now());
  }

  private void expire(Checkpoint c) {
    if (!due(c)) return;
    if ("running".equals(c.status)) {
      var active = current(c);
      boolean unknown = external(c, active);
      finishAttempt(c, unknown ? "unknown" : "expired", null);
      releaseBudget(c, active);
      if (unknown) {
        c.status = "unknown";
        c.error = "external_result_unknown";
        event(c, "node.unknown", active.id(), active.type(), null, c.error, null);
      }
    }
    if (!Set.of("completed", "failed", "unknown", "terminated").contains(c.status))
      c.status = "expired";
    c.purged = true;
    c.input = Map.of();
    c.outputs.clear();
    c.output = Map.of();
    c.approved = null;
    c.waiting = null;
    db.update("DELETE FROM workflow_local_effect WHERE run_id=?", c.id);
    event(c, "content.expired", null, null, null, null, null);
    save(c);
  }

  private Graph.Node current(Checkpoint c) {
    return c.graph.nodes().stream().filter(n -> n.id().equals(c.node)).findFirst().orElseThrow();
  }

  private void finishAttempt(Checkpoint c, String status, Long elapsed) {
    db.update(
        "UPDATE workflow_step_attempt SET status=?, finished_at=?, elapsed_ms=? WHERE run_id=? AND activation_no=? AND attempt_no=? AND status='running'",
        status,
        Timestamp.from(Instant.now()),
        elapsed,
        c.id,
        c.activation,
        c.attempt);
  }

  /**
   * A single worker services one activation at a time. The claim token also fences late results.
   */
  public boolean tick() {
    maintain();
    var next =
        db.query(
            "SELECT app_key,run_id FROM workflow_run WHERE status='queued' ORDER BY created_at,run_id LIMIT 1",
            (r, i) -> List.of(r.getString(1), r.getString(2)));
    if (next.isEmpty()) return false;
    Activation activation = tx.execute(t -> claim(next.get(0).get(0), next.get(0).get(1)));
    if (activation == null) return true;
    var c = activation.state();
    var n = activation.node();
    long started = System.nanoTime();
    NodeHandler.Outcome outcome;
    try {
      Map<String, Object> input = inputOf(c, n);
      outcome = registry.evaluate(n, input, new NodeHandler.Context(c.mode, false, c.approved));
      validateLocalResult(c, n, outcome);
    } catch (RuntimeException failure) {
      boolean invalidOutput = failure instanceof MeetingTaskExtractor.InvalidOutputException;
      boolean invalidResult = failure instanceof GraphIssue || failure instanceof BadResult;
      boolean uncertain = external(c, n) && !invalidOutput;
      Map<String, Object> usage = null;
      if (failure instanceof MeetingTaskExtractor.InvalidOutputException invalid) {
        var measured = invalid.usage();
        if (measured != null) {
          var values = new LinkedHashMap<String, Object>();
          values.put("model", measured.model());
          values.put("inputTokens", measured.inputTokens());
          values.put("outputTokens", measured.outputTokens());
          usage = safeUsage(values);
        }
      }
      var measuredUsage = usage;
      long elapsed = elapsed(started);
      tx.executeWithoutResult(
          t -> {
            var row = load(c.app, c.id, true);
            if (matches(row, c)) {
              row.status = uncertain ? "unknown" : "failed";
              row.error =
                  invalidOutput
                      ? "invalid_model_output"
                      : uncertain
                          ? "external_result_unknown"
                          : invalidResult ? "node_result_invalid" : "node_failed";
              releaseBudget(row, n);
              finishAttempt(row, row.status, elapsed);
              event(row, "node." + row.status, n.id(), n.type(), elapsed, row.error, measuredUsage);
              save(row);
            }
          });
      return true;
    }
    long elapsed = elapsed(started);
    tx.executeWithoutResult(
        t -> {
          var row = load(c.app, c.id, true);
          expire(row);
          if (matches(row, c)) commit(row, n, outcome, elapsed);
        });
    return true;
  }

  private boolean external(Checkpoint c, Graph.Node n) {
    return !Boolean.TRUE.equals(c.replayPolicies.get(n.type()));
  }

  private static boolean usesModel(Checkpoint c, Graph.Node n) {
    return "live".equals(c.mode)
        && c.liveModelPolicies.getOrDefault(n.type(), "extract-tasks".equals(n.type()));
  }

  private String budgetKey(Checkpoint c) {
    return "workflow-" + c.id + "-" + c.activation;
  }

  private void releaseBudget(Checkpoint c, Graph.Node n) {
    if (usesModel(c, n)) budget.finish(budgetKey(c));
  }

  private static long elapsed(long since) {
    return Duration.ofNanos(System.nanoTime() - since).toMillis();
  }

  private static boolean matches(Checkpoint row, Checkpoint claim) {
    return "running".equals(row.status)
        && row.activation == claim.activation
        && row.attempt == claim.attempt;
  }

  private Activation claim(String app, String id) {
    var c = load(app, id, true);
    expire(c);
    if (!"queued".equals(c.status)) return null;
    var n = current(c);
    String activeVersion;
    try {
      activeVersion = registry.version(n.type());
    } catch (GraphIssue missing) {
      activeVersion = null;
    }
    if (activeVersion == null || !Objects.equals(c.capabilities.get(n.type()), activeVersion)) {
      c.status = "failed";
      c.error = "capability_version_unavailable";
      event(c, "run.failed", n.id(), n.type(), null, c.error, null);
      save(c);
      return null;
    }
    try {
      // Reject unbound or mistyped input before reserving an external call.
      registry.checkInputs(n, inputOf(c, n));
    } catch (GraphIssue badInput) {
      c.status = "failed";
      c.error = "node_input_invalid";
      event(c, "node.failed", n.id(), n.type(), null, c.error, null);
      save(c);
      return null;
    }
    if (usesModel(c, n))
      try {
        budget.reserve(budgetKey(c));
      } catch (GraphIssue refusal) {
        c.status = "failed";
        c.error =
            switch (refusal.status()) {
              case 429 -> "live_budget_exhausted";
              case 409 -> "live_busy";
              default -> "live_unavailable";
            };
        event(c, "run.failed", n.id(), n.type(), null, c.error, null);
        save(c);
        return null;
      }
    c.status = "running";
    c.attempt++;
    c.startedAt = Instant.now().toString();
    if (c.path.isEmpty() || !c.path.get(c.path.size() - 1).equals(n.id())) c.path.add(n.id());
    db.update(
        "INSERT INTO workflow_step_attempt(run_id,activation_no,attempt_no,node_id,status,started_at) VALUES(?,?,?,?,?,?)",
        c.id,
        c.activation,
        c.attempt,
        n.id(),
        c.status,
        timestamp(c.startedAt));
    event(c, "node.started", n.id(), n.type(), null, null, null);
    save(c);
    return new Activation(c, n);
  }

  private Map<String, Object> inputOf(Checkpoint c, Graph.Node node) {
    return "start".equals(node.type()) ? c.input : registry.bind(node, c.outputs);
  }

  private static final class BadResult extends RuntimeException {}

  private static void validateLocalResult(Checkpoint c, Graph.Node n, NodeHandler.Outcome result) {
    if (result == null
        || result.effects() == null
        || result.effects().size() > 50
        || (result.waiting() && !result.effects().isEmpty())) throw new BadResult();
    if (!result.waiting()
        && !"end".equals(n.type())
        && c.graph.edges().stream()
            .noneMatch(
                edge -> n.id().equals(edge.from()) && Objects.equals(result.port(), edge.port())))
      throw new BadResult();
    for (var effect : result.effects()) {
      if (effect == null
          || !(effect.get("kind") instanceof String kind)
          || !kind.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}")
          || json(effect).getBytes(StandardCharsets.UTF_8).length > 65535) throw new BadResult();
    }
  }

  @SuppressWarnings("unchecked")
  private void commit(Checkpoint c, Graph.Node n, NodeHandler.Outcome result, long duration) {
    releaseBudget(c, n);
    var output = new LinkedHashMap<>(result.outputs());
    var usage = safeUsage(output.remove("_usage"));
    c.outputs.put(n.id(), output);
    if (result.waiting()) {
      c.status = "waiting";
      List<Map<String, Object>> items =
          (List<Map<String, Object>>) output.getOrDefault("items", List.of());
      c.waiting =
          new WaitPrompt(
              UUID.randomUUID().toString(),
              n.id(),
              "选择需要保留的事项",
              items,
              List.of(new Field("selectedIds", "multi-select", "确认事项")),
              Instant.now().toString());
      finishAttempt(c, "waiting", duration);
      event(c, "node.waiting", n.id(), n.type(), duration, null, usage);
      save(c);
      return;
    }
    int index = 0;
    for (Map<String, Object> effect : result.effects()) {
      db.update(
          "INSERT INTO workflow_local_effect(run_id,activation_no,position_no,kind,effect_json) VALUES(?,?,?,?,?)",
          c.id,
          c.activation,
          ++index,
          effect.get("kind"),
          json(effect));
    }
    finishAttempt(c, "completed", duration);
    var chosen =
        c.graph.edges().stream()
            .filter(
                edge -> n.id().equals(edge.from()) && Objects.equals(result.port(), edge.port()))
            .findFirst();
    append(
        c,
        "node.completed",
        n.id(),
        n.type(),
        duration,
        null,
        usage,
        result.port(),
        chosen.map(Graph.Edge::to).orElse(null),
        null);
    c.approved = null;
    c.waiting = null;
    if ("end".equals(n.type())) {
      c.output = output;
      c.status = "completed";
      c.pause = false;
      event(c, "run.completed", null, null, null, null, null);
    } else {
      if (chosen.isEmpty()) {
        c.status = "failed";
        c.error = "route_missing";
        event(c, "run.failed", n.id(), n.type(), null, c.error, null);
      } else {
        c.node = chosen.get().to();
        c.activation++;
        c.attempt = 0;
        boolean paused = c.pause || n.id().equals(c.pauseAfter);
        c.pause = false;
        c.status = paused ? "paused" : "queued";
        if (paused) {
          c.pauseAfter = null;
          event(c, "run.paused", null, null, null, null, null);
        }
      }
    }
    save(c);
  }

  private static Map<String, Object> safeUsage(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) return null;
    Map<String, Object> usage = new LinkedHashMap<>();
    for (String k : List.of("inputTokens", "outputTokens"))
      if (map.get(k) instanceof Number n
          && (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte)
          && n.longValue() >= 0) usage.put(k, n.longValue());
    if (map.get("model") instanceof String name && name.matches("[A-Za-z0-9._:/-]{1,150}"))
      usage.put("model", name);
    return usage.isEmpty() ? null : usage;
  }

  private void maintain() {
    var now = Timestamp.from(Instant.now());
    var stale =
        db.query(
            "SELECT app_key,run_id FROM workflow_run WHERE expires_at<=? OR (status='running' AND lease_until<=?) LIMIT 20",
            (r, i) -> List.of(r.getString(1), r.getString(2)),
            now,
            now);
    for (var id : stale)
      tx.executeWithoutResult(
          t -> {
            var c = load(id.get(0), id.get(1), true);
            expire(c);
            if (!"running".equals(c.status)
                || Instant.parse(c.startedAt).plus(LEASE).isAfter(Instant.now())) return;
            var n = current(c);
            releaseBudget(c, n);
            finishAttempt(c, "interrupted", null);
            if (external(c, n)) {
              c.status = "unknown";
              c.error = "external_result_unknown";
            } else {
              c.status = c.pause ? "paused" : "queued";
              c.pause = false;
            }
            event(c, "node.interrupted", n.id(), n.type(), null, c.error, null);
            save(c);
          });
  }

  public View signal(String app, String id, String action, Signal request) {
    return signal(app, id, action, request, null);
  }

  public View review(String app, String id, Review review) {
    if (!"verified".equals(review.decision()) && !"terminate".equals(review.decision()))
      throw new Fault(400, "核对决定无效。");
    String ref = review.receiptRef();
    if (ref != null && !ref.matches("[A-Za-z0-9._:-]{1,128}")) throw new Fault(400, "回执编号格式无效。");
    return signal(
        app,
        id,
        "reconcile." + review.decision(),
        new Signal(review.signalId(), null, null),
        ref == null ? null : hash(ref));
  }

  private View signal(
      String app, String id, String action, Signal request, String receiptFingerprint) {
    String signal = key(request.signalId());
    Map<String, Object> values = request.values() == null ? Map.of() : request.values();
    List<String> selected = List.of();
    if ("resume".equals(action)) {
      if (request.waitId() == null
          || values.size() != 1
          || !(values.get("selectedIds") instanceof List<?> ids)
          || ids.size() > 100) throw new Fault(400, "确认信号需要有效的 waitId 和 selectedIds。");
      Set<String> unique = new TreeSet<>();
      for (Object value : ids)
        if (!(value instanceof String s) || s.length() > 128 || !unique.add(s))
          throw new Fault(400, "事项编号不可重复或无效。");
      selected = List.copyOf(unique);
    } else if (request.waitId() != null || !values.isEmpty()) throw new Fault(400, "此操作不接受确认值。");
    List<String> decision = selected;
    var signature = hash(Arrays.asList(action, request.waitId(), decision, receiptFingerprint));
    return tx.execute(
        t -> {
          var c = load(app, id, true);
          expire(c);
          var existing =
              db.queryForList(
                  "SELECT payload_hash FROM workflow_signal WHERE run_id=? AND signal_id=?",
                  String.class,
                  id,
                  signal);
          if (!existing.isEmpty()) {
            if (!signature.equals(existing.get(0))) throw new Fault(409, "信号编号已用于不同操作。");
            return view(c);
          }
          String waitStart = null;
          switch (action) {
            case "retry" -> {
              if (!canRetry(c)) throw new Fault(409, "当前节点的失败不支持安全重试。");
              c.status = "queued";
              c.error = null;
            }
            case "reconcile.verified", "reconcile.terminate" -> {
              if (!"unknown".equals(c.status)) throw new Fault(409, "只有外部结果未知的运行可以人工核对。");
              if ("reconcile.verified".equals(action)) c.reviewed = true;
              if (receiptFingerprint != null) c.receiptFingerprint = receiptFingerprint;
              if ("reconcile.terminate".equals(action)) c.status = "terminated";
            }
            case "resume" -> {
              if (!"waiting".equals(c.status) || !c.waiting.waitId().equals(request.waitId()))
                throw new Fault(409, "等待项已变化。");
              var offered = c.waiting.items().stream().map(it -> it.get("id")).toList();
              if (!offered.containsAll(decision)) throw new Fault(400, "选择包含不存在的事项。");
              waitStart = c.waiting.since();
              if (waitStart == null) {
                // Prior checkpoints already recorded node.waiting. Never estimate from run
                // creation.
                var last =
                    db.query(
                        "SELECT event_json FROM workflow_event WHERE run_id=? AND seq=?",
                        (r, i) -> JSON.readValue(r.getString(1), Event.class),
                        c.id,
                        c.seq);
                if (!last.isEmpty()
                    && "node.waiting".equals(last.get(0).type())
                    && c.node.equals(last.get(0).nodeId())) waitStart = last.get(0).occurredAt();
              }
              c.approved = decision;
              c.waiting = null;
              c.status = "queued";
            }
            case "pause" -> {
              if ("queued".equals(c.status)) c.status = "paused";
              else if ("running".equals(c.status)) c.pause = true;
              else if (!"paused".equals(c.status)) throw new Fault(409, "当前运行无法暂停。");
            }
            case "continue" -> {
              if (!"paused".equals(c.status)) throw new Fault(409, "当前运行未暂停。");
              c.status = "queued";
            }
            default -> throw new Fault(400, "未知操作。");
          }
          db.update(
              "INSERT INTO workflow_signal(run_id,signal_id,payload_hash) VALUES(?,?,?)",
              id,
              signal,
              signature);
          append(
              c,
              "signal." + action,
              c.node,
              current(c).type(),
              null,
              null,
              null,
              null,
              null,
              null,
              waitStart);
          save(c);
          return view(c);
        });
  }
}
