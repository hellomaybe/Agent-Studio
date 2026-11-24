package studio.agent.graph;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.agent.model.MeetingTaskExtractor;
import studio.agent.quality.ReleaseChecks;
import tools.jackson.databind.json.JsonMapper;

@Service
public class GraphCatalog {
  public record Summary(String id, String name, long revision, long version) {}

  public record Draft(
      String appId, long revision, long version, Graph graph, boolean liveEnabled) {}

  public record Version(
      String appId, long version, long revision, Graph graph, Map<String, String> capabilities) {}

  public record VersionInfo(long version, long revision) {}

  public record Sample(String id, String label, String text) {}

  private final JdbcTemplate db;
  private final NodeRegistry nodes;
  private final JsonMapper json;
  private final MeetingTaskExtractor model;
  private final ReleaseChecks releaseChecks;

  public GraphCatalog(
      JdbcTemplate db,
      NodeRegistry nodes,
      JsonMapper json,
      MeetingTaskExtractor model,
      ReleaseChecks releaseChecks) {
    this.db = db;
    this.nodes = nodes;
    this.json = json;
    this.model = model;
    this.releaseChecks = releaseChecks;
  }

  public List<Summary> apps() {
    return db.query(
        "SELECT app_id,display_name,revision,latest_version FROM studio_graph_app ORDER BY app_id",
        (r, i) -> new Summary(r.getString(1), r.getString(2), r.getLong(3), r.getLong(4)));
  }

  public Draft draft(String appId) {
    return draft(appId, false);
  }

  private Draft draft(String appId, boolean lock) {
    var rows =
        db.query(
            "SELECT revision,latest_version,graph_json FROM studio_graph_app WHERE app_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (r, i) ->
                new Draft(
                    appId,
                    r.getLong(1),
                    r.getLong(2),
                    json.readValue(r.getString(3), Graph.class),
                    model.available()),
            appId);
    if (rows.isEmpty()) throw missing();
    return rows.get(0);
  }

  public List<Sample> samples(String appId) {
    return switch (appId) {
      case "meeting-notes" ->
          List.of(
              new Sample(
                  "assigned", "含负责人和待分配事项", "Alice: Send the release report\n- Review the roadmap"),
              new Sample("empty", "无待办", "No action was agreed."));
      case "feedback-triage" ->
          List.of(
              new Sample("urgent", "紧急反馈", "Urgent: payment is blocked"),
              new Sample("normal", "普通建议", "Please add a dark theme"));
      default -> throw missing();
    };
  }

  @Transactional
  public Draft save(String appId, long revision, Graph graph) {
    var d = draft(appId, true);
    checkRevision(d, revision);
    nodes.validateDraft(graph);
    String serialized = json.writeValueAsString(graph);
    if (serialized.length() > 160000)
      throw GraphIssue.bad(null, "graph", "Graph exceeds the storage limit");
    int changed =
        db.update(
            "UPDATE studio_graph_app SET graph_json=?,revision=revision+1 WHERE app_id=? AND"
                + " revision=?",
            serialized,
            appId,
            revision);
    if (changed != 1) throw stale();
    return draft(appId);
  }

  @Transactional
  public Version publish(String appId, long revision) {
    var d = draft(appId, true);
    checkRevision(d, revision);
    nodes.validate(d.graph());
    var existing =
        db.query(
            "SELECT version_no FROM studio_graph_version WHERE app_id=? AND revision=?",
            (r, i) -> r.getLong(1),
            appId,
            revision);
    if (!existing.isEmpty()) return readVersion(appId, existing.get(0));
    releaseChecks.requirePassing(appId, revision, d.version(), d.graph());
    long version = d.version() + 1;
    var versions = capabilities(d.graph());
    db.update(
        "INSERT INTO studio_graph_version(app_id,version_no,revision,graph_json,capabilities_json)"
            + " VALUES(?,?,?,?,?)",
        appId,
        version,
        revision,
        json.writeValueAsString(d.graph()),
        json.writeValueAsString(versions));
    db.update("UPDATE studio_graph_app SET latest_version=? WHERE app_id=?", version, appId);
    return new Version(appId, version, revision, d.graph(), versions);
  }

  private Map<String, String> capabilities(Graph g) {
    Map<String, String> result = new TreeMap<>();
    g.nodes().forEach(n -> result.put(n.type(), nodes.version(n.type())));
    return result;
  }

  public List<VersionInfo> versions(String appId) {
    var list =
        db.query(
            "SELECT version_no,revision FROM studio_graph_version WHERE app_id=? ORDER BY"
                + " version_no DESC",
            (r, i) -> new VersionInfo(r.getLong(1), r.getLong(2)),
            appId);
    if (list.isEmpty()) throw missing();
    return list;
  }

  public Version resolve(String appId, Long version) {
    Version frozen = readVersion(appId, version);
    Map<String, String> registered;
    try {
      registered = capabilities(frozen.graph());
    } catch (GraphIssue removed) {
      throw unavailable();
    }
    if (!registered.equals(frozen.capabilities())) throw unavailable();
    return frozen;
  }

  private static GraphIssue unavailable() {
    return new GraphIssue(
        409, "A capability version in this graph is unavailable", null, null, null);
  }

  /**
   * Viewing an old definition remains possible after the executable capability has been retired.
   */
  public Version readVersion(String appId, Long version) {
    String query =
        "SELECT version_no,revision,graph_json,capabilities_json FROM studio_graph_version WHERE"
            + " app_id=? AND version_no=";
    List<Version> list =
        db.query(
            query
                + (version == null
                    ? "(SELECT latest_version FROM studio_graph_app WHERE app_id=?)"
                    : "?"),
            (r, i) -> {
              Graph g = json.readValue(r.getString(3), Graph.class);
              var frozen =
                  json.readValue(
                      r.getString(4),
                      new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
              return new Version(appId, r.getLong(1), r.getLong(2), g, Map.copyOf(frozen));
            },
            appId,
            version == null ? appId : version);
    if (list.isEmpty()) throw missing();
    return list.get(0);
  }

  static void checkRevision(Draft d, long revision) {
    if (d.revision() != revision) throw stale();
  }

  private static GraphIssue missing() {
    return new GraphIssue(404, "Application or version not found", null, null, null);
  }

  private static GraphIssue stale() {
    return new GraphIssue(
        409, "The draft revision changed. Reload and merge before saving.", null, "revision", null);
  }
}
