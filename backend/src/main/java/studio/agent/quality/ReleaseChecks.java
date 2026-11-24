package studio.agent.quality;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import studio.agent.graph.Graph;
import studio.agent.graph.NodeRegistry;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records accepted fixture results against the graph semantics, capability versions and suite
 * revision.
 */
@Service
public class ReleaseChecks {
  public record Fixture(
      String name,
      String text,
      Boolean approve,
      Boolean expectWait,
      Integer effects,
      String field,
      String expectedValue) {
    public Fixture(String name, String text, Boolean approve, Boolean expectWait, Integer effects) {
      this(name, text, approve, expectWait, effects, null, null);
    }

    boolean checksField() {
      return field != null && !field.isBlank();
    }
  }

  public record Suite(long revision, List<Fixture> cases) {}

  private final JdbcTemplate db;
  private final NodeRegistry nodes;
  private final JsonMapper json =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  public ReleaseChecks(JdbcTemplate db, NodeRegistry nodes) {
    this.db = db;
    this.nodes = nodes;
  }

  public Suite suite(String appId) {
    var rows =
        db.query(
            "SELECT revision,cases_json FROM studio_evaluation_suite WHERE app_id=?",
            (r, i) ->
                new Suite(r.getLong(1), List.of(json.readValue(r.getString(2), Fixture[].class))),
            appId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到此应用的样例套件");
    return rows.get(0);
  }

  public Suite save(String id, Suite input) {
    if (input.cases() == null || input.cases().isEmpty() || input.cases().size() > 20)
      throw invalid("请提供 1 至 20 个验收样例");
    var names = new HashSet<String>();
    for (var f : input.cases()) {
      if (f == null
          || f.name() == null
          || f.name().isBlank()
          || f.name().length() > 80
          || !names.add(f.name().strip())
          || f.text() == null
          || f.text().isBlank()
          || f.text().length() > 8000
          || f.approve() == null
          || f.expectWait() == null
          || f.effects() == null
          || f.effects() < 0
          || f.effects() > 100) throw invalid("请检查样例名称、文本和预期结果");
      if (f.checksField()
          && (!f.field().matches("[A-Za-z0-9_-]{1,64}\\.[A-Za-z0-9_-]{1,64}")
              || f.expectedValue() == null
              || f.expectedValue().length() > 500)) throw invalid("字段断言需要 nodeId.field 和预期标量值");
    }
    int changed =
        db.update(
            "UPDATE studio_evaluation_suite SET revision=revision+1,cases_json=?,passed_hash=NULL,passed_revision=NULL,compared_version=NULL WHERE app_id=? AND revision=?",
            json.writeValueAsString(input.cases()),
            id,
            input.revision());
    if (changed != 1) throw conflict("样例已经改变，请重新载入");
    return new Suite(input.revision() + 1, List.copyOf(input.cases()));
  }

  public String fingerprint(Graph graph, Suite suite) {
    var steps =
        graph.nodes().stream()
            .sorted(Comparator.comparing(Graph.Node::id))
            .map(
                n ->
                    Map.of(
                        "id",
                        n.id(),
                        "type",
                        n.type(),
                        "version",
                        nodes.version(n.type()),
                        "config",
                        n.config(),
                        "bindings",
                        n.bindings()))
            .toList();
    var edges =
        graph.edges().stream()
            .sorted(
                Comparator.comparing(Graph.Edge::from)
                    .thenComparing(Graph.Edge::port)
                    .thenComparing(Graph.Edge::to))
            .toList();
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(
              digest.digest(
                  json.writeValueAsString(List.of(steps, edges, suite))
                      .getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  @Transactional
  public void record(
      String id, long revision, long version, Suite suite, String fingerprint, boolean passed) {
    var active =
        db.query(
            "SELECT revision,latest_version FROM studio_graph_app WHERE app_id=? FOR UPDATE",
            (r, i) -> r.getLong(1) == revision && r.getLong(2) == version,
            id);
    if (!active.equals(List.of(true))) throw conflict("评估期间草稿或发布版本已改变，请重新评估");
    if (db.update(
            "UPDATE studio_evaluation_suite SET passed_hash=?,passed_revision=?,compared_version=? WHERE app_id=? AND revision=?",
            passed ? fingerprint : null,
            revision,
            version,
            id,
            suite.revision())
        != 1) throw conflict("评估期间样例已改变，请重新评估");
  }

  /** Called inside the publication transaction, after locking the draft. */
  public void requirePassing(String id, long revision, long version, Graph graph) {
    var accepted =
        db.query(
            "SELECT revision,cases_json,passed_hash,passed_revision,compared_version FROM studio_evaluation_suite WHERE app_id=? FOR UPDATE",
            (r, i) ->
                revision == r.getLong("passed_revision")
                    && version == r.getLong("compared_version")
                    && fingerprint(
                            graph,
                            new Suite(
                                r.getLong(1),
                                List.of(json.readValue(r.getString(2), Fixture[].class))))
                        .equals(r.getString("passed_hash")),
            id);
    if (!accepted.equals(List.of(true))) throw conflict("请先用当前草稿和样例完成一次全部通过的评估");
  }

  static ResponseStatusException invalid(String msg) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
  }

  static ResponseStatusException conflict(String msg) {
    return new ResponseStatusException(HttpStatus.CONFLICT, msg);
  }
}
