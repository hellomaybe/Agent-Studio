package studio.agent.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import studio.agent.graph.Graph;
import studio.agent.graph.GraphCatalog;
import studio.agent.graph.GraphIssue;
import studio.agent.graph.NodeHandler;
import studio.agent.graph.NodeRegistry;

@RestController
@RequestMapping("/api/apps/{app}/")
public class EvaluationController {
  public record Request(long revision, Graph graph) {}

  public record Score(boolean pass, String detail) {}

  public record Case(String name, String expected, Score baseline, Score candidate) {}

  public record Report(
      long revision, long version, long suiteRevision, String fingerprint, List<Case> cases) {}

  private final GraphCatalog catalog;
  private final NodeRegistry nodes;
  private final ReleaseChecks checks;

  public EvaluationController(GraphCatalog catalog, NodeRegistry nodes, ReleaseChecks checks) {
    this.catalog = catalog;
    this.nodes = nodes;
    this.checks = checks;
  }

  @GetMapping("cases")
  public Map<String, Object> cases(@PathVariable String app) {
    return Map.of("data", checks.suite(app));
  }

  @PutMapping("cases")
  public Map<String, Object> save(@PathVariable String app, @RequestBody ReleaseChecks.Suite body) {
    return Map.of("data", checks.save(app, body));
  }

  @PostMapping("evaluations")
  public Map<String, Object> evaluate(@PathVariable String app, @RequestBody Request request) {
    var draft = catalog.draft(app);
    if (request.revision() != draft.revision()) throw ReleaseChecks.conflict("草稿版本已改变，请重新载入");
    var graph = request.graph() == null ? draft.graph() : request.graph();
    nodes.validate(graph);
    var release = catalog.resolve(app, null);
    var suite = checks.suite(app);
    String hash = checks.fingerprint(graph, suite);
    var results = new ArrayList<Case>();
    for (var fixture : suite.cases()) {
      var expected = (fixture.expectWait() ? "等待人工" : "直接结束") + " · " + fixture.effects() + " 项操作";
      if (fixture.checksField())
        expected += " · " + fixture.field() + " = " + fixture.expectedValue();
      results.add(
          new Case(
              fixture.name(), expected, score(release.graph(), fixture), score(graph, fixture)));
    }
    // A comparison on an unsaved draft cannot qualify different persisted code for publication.
    if (checks.fingerprint(draft.graph(), suite).equals(hash)) {
      checks.record(
          app,
          draft.revision(),
          release.version(),
          suite,
          hash,
          results.stream().allMatch(c -> c.candidate().pass()));
    }
    return Map.of(
        "data", new Report(draft.revision(), release.version(), suite.revision(), hash, results));
  }

  /** Execute the registered sample handlers without the runtime's external-effect sink. */
  private Score score(Graph graph, ReleaseChecks.Fixture fixture) {
    var byId =
        graph.nodes().stream().collect(Collectors.toMap(Graph.Node::id, Function.identity()));
    var current =
        graph.nodes().stream().filter(n -> "start".equals(n.type())).findFirst().orElseThrow();
    var prior = new LinkedHashMap<String, Map<String, Object>>();
    boolean waited = false;
    int effects = 0;
    try {
      for (int i = 0; i < graph.nodes().size(); i++) {
        var inputs =
            current.type().equals("start")
                ? Map.<String, Object>of("text", fixture.text())
                : nodes.bind(current, prior);
        var result = nodes.evaluate(current, inputs, new NodeHandler.Context("sample", true, null));
        if (result.waiting()) {
          waited = true;
          List<?> items = (List<?>) result.outputs().getOrDefault("items", List.of());
          var ids =
              fixture.approve()
                  ? items.stream()
                      .map(item -> String.valueOf(((Map<?, ?>) item).get("id")))
                      .toList()
                  : List.<String>of();
          result = nodes.evaluate(current, inputs, new NodeHandler.Context("sample", true, ids));
        }
        prior.put(current.id(), result.outputs());
        effects += result.effects().size();
        if ("end".equals(current.type())) {
          String summary = (waited ? "等待人工" : "直接结束") + " · " + effects + " 项操作";
          boolean match = waited == fixture.expectWait() && effects == fixture.effects();
          if (fixture.checksField()) {
            String[] ref = fixture.field().split("\\.");
            Object actual = prior.getOrDefault(ref[0], Map.of()).get(ref[1]);
            boolean scalar =
                actual instanceof String || actual instanceof Boolean || actual instanceof Number;
            match = match && scalar && actual.toString().equals(fixture.expectedValue());
            summary += " · " + fixture.field() + " = " + (scalar ? actual : "未取得标量");
          }
          return new Score(match, summary);
        }
        String id = current.id(), port = result.port();
        current =
            byId.get(
                graph.edges().stream()
                    .filter(e -> e.from().equals(id) && e.port().equals(port))
                    .findFirst()
                    .orElseThrow()
                    .to());
      }
      return new Score(false, "未到达结束节点");
    } catch (GraphIssue problem) {
      return new Score(false, current.id() + " · " + problem.getMessage());
    } catch (RuntimeException failure) {
      return new Score(false, current.id() + " · 节点无法执行固定样例");
    }
  }

  @ExceptionHandler(GraphIssue.class)
  ResponseEntity<?> graphError(GraphIssue issue) {
    return ResponseEntity.status(issue.status()).body(Map.of("message", issue.getMessage()));
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> conflict(ResponseStatusException issue) {
    return ResponseEntity.status(issue.getStatusCode())
        .body(Map.of("message", String.valueOf(issue.getReason())));
  }
}
