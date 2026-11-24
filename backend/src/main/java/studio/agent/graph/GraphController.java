package studio.agent.graph;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import studio.agent.model.MeetingTaskExtractor;

@RestController
@RequestMapping("/api")
public class GraphController {
  public record Data<T>(T data) {}

  public record Save(long revision, Graph graph) {}

  public record Publish(long revision) {}

  public record Debug(
      Long revision,
      Long version,
      Graph graph,
      String nodeId,
      String mode,
      Map<String, Object> inputs,
      String requestId) {}

  public record DebugResult(
      String nodeId,
      String mode,
      Map<String, Object> outputs,
      String port,
      boolean waiting,
      List<Map<String, Object>> effects,
      long durationMs) {}

  private final GraphCatalog catalog;
  private final NodeRegistry nodes;
  private final LiveBudget budget;

  public GraphController(GraphCatalog catalog, NodeRegistry nodes, LiveBudget budget) {
    this.catalog = catalog;
    this.nodes = nodes;
    this.budget = budget;
  }

  @GetMapping("/apps")
  public Data<List<GraphCatalog.Summary>> apps() {
    return new Data<>(catalog.apps());
  }

  @GetMapping("/nodes")
  public Data<List<NodeHandler.Descriptor>> nodes() {
    return new Data<>(nodes.descriptors());
  }

  @GetMapping("/apps/{id}/samples")
  public Data<List<GraphCatalog.Sample>> samples(@PathVariable String id) {
    return new Data<>(catalog.samples(id));
  }

  @GetMapping("/apps/{id}/draft")
  public Data<GraphCatalog.Draft> draft(@PathVariable String id) {
    return new Data<>(catalog.draft(id));
  }

  @PutMapping("/apps/{id}/draft")
  public Data<GraphCatalog.Draft> save(@PathVariable String id, @RequestBody Save request) {
    return new Data<>(catalog.save(id, request.revision(), request.graph()));
  }

  @PostMapping("/apps/{id}/publish")
  public Data<GraphCatalog.Version> publish(@PathVariable String id, @RequestBody Publish request) {
    return new Data<>(catalog.publish(id, request.revision()));
  }

  @GetMapping("/apps/{id}/versions")
  public Data<List<GraphCatalog.VersionInfo>> versions(@PathVariable String id) {
    return new Data<>(catalog.versions(id));
  }

  @GetMapping("/apps/{id}/versions/{version}")
  public Data<GraphCatalog.Version> version(@PathVariable String id, @PathVariable long version) {
    return new Data<>(catalog.readVersion(id, version));
  }

  @PostMapping("/apps/{id}/debug")
  public Data<DebugResult> debug(@PathVariable String id, @RequestBody Debug req) {
    Graph g;
    if (req.version() != null) {
      if (req.graph() != null)
        throw GraphIssue.bad(null, "version", "Choose either a graph or a published version");
      g = catalog.resolve(id, req.version()).graph();
    } else {
      var d = catalog.draft(id);
      if (req.revision() == null)
        throw GraphIssue.bad(null, "revision", "Draft revision is required");
      GraphCatalog.checkRevision(d, req.revision());
      g = req.graph() == null ? d.graph() : req.graph();
      nodes.validateDraft(g);
    }
    var n =
        g.nodes().stream()
            .filter(a -> a.id().equals(req.nodeId()))
            .findFirst()
            .orElseThrow(() -> GraphIssue.bad(req.nodeId(), "id", "Debug target not found"));
    boolean live = "live".equals(req.mode());
    if (!live && !"sample".equals(req.mode()))
      throw GraphIssue.bad(n.id(), "mode", "Choose sample or live");
    String key = null;
    if (live) {
      if (!nodes.usesLiveModel(n.type()))
        throw GraphIssue.bad(n.id(), "mode", "Only model nodes support live debugging");
      if (req.requestId() == null || !req.requestId().matches("[A-Za-z0-9_-]{1,80}"))
        throw GraphIssue.bad(n.id(), "requestId", "Live debug requires a request ID");
      nodes.checkInputs(n, req.inputs());
      key = "debug:" + id + ":" + req.requestId();
      budget.reserve(key);
    }
    long start = System.nanoTime();
    try {
      var result = nodes.evaluate(n, req.inputs(), new NodeHandler.Context(req.mode(), true, null));
      return new Data<>(
          new DebugResult(
              n.id(),
              req.mode(),
              result.outputs(),
              result.port(),
              result.waiting(),
              result.effects(),
              (System.nanoTime() - start) / 1_000_000));
    } catch (MeetingTaskExtractor.InvalidOutputException invalid) {
      var ex = new GraphIssue(502, invalid.getMessage(), n.id(), "outputs", null);
      var usage = invalid.usage();
      ex.diagnostics.put("model", usage.model());
      if (usage.inputTokens() >= 0) ex.diagnostics.put("inputTokens", usage.inputTokens());
      if (usage.outputTokens() >= 0) ex.diagnostics.put("outputTokens", usage.outputTokens());
      ex.diagnostics.put("durationMs", (System.nanoTime() - start) / 1_000_000);
      throw ex;
    } catch (IllegalStateException provider) {
      throw new GraphIssue(502, "Node execution failed", n.id(), null, null);
    } finally {
      if (key != null) budget.finish(key);
    }
  }
}
