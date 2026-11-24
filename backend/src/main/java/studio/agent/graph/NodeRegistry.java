package studio.agent.graph;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class NodeRegistry {
  public boolean usesLiveModel(String type) {
    return handler(type).descriptor().liveModel();
  }

  private final Map<String, NodeHandler> handlers;

  public NodeRegistry(List<NodeHandler> beans) {
    handlers =
        beans.stream()
            .collect(Collectors.toUnmodifiableMap(h -> h.descriptor().type(), Function.identity()));
  }

  public List<NodeHandler.Descriptor> descriptors() {
    return handlers.values().stream()
        .map(NodeHandler::descriptor)
        .sorted(Comparator.comparing(NodeHandler.Descriptor::type))
        .toList();
  }

  public String version(String type) {
    return handler(type).descriptor().version();
  }

  public boolean replaySafe(String type, String mode) {
    return handler(type).replaySafe(mode);
  }

  private NodeHandler handler(String type) {
    var h = type == null ? null : handlers.get(type);
    if (h == null) throw GraphIssue.bad(null, "type", "Unknown node type: " + type);
    return h;
  }

  public Map<String, Object> bind(Graph.Node node, Map<String, Map<String, Object>> prior) {
    var inputs = new LinkedHashMap<String, Object>();
    for (var e : node.bindings().entrySet()) {
      String[] ref = e.getValue().split("\\.", -1);
      if (ref.length != 2 || !prior.getOrDefault(ref[0], Map.of()).containsKey(ref[1]))
        throw GraphIssue.bad(node.id(), e.getKey(), "Missing upstream output " + e.getValue());
      inputs.put(e.getKey(), prior.get(ref[0]).get(ref[1]));
    }
    return inputs;
  }

  public NodeHandler.Outcome evaluate(
      Graph.Node node, Map<String, Object> inputs, NodeHandler.Context context) {
    var implementation = handler(node.type());
    var d = implementation.descriptor();
    if (context == null || !("sample".equals(context.mode()) || "live".equals(context.mode())))
      throw GraphIssue.bad(node.id(), "mode", "Expected sample or live");
    validateValues(node.id(), inputs, d.inputs(), false);
    if ("live".equals(context.mode())) implementation.validateLiveInput(node, inputs);
    var config = new LinkedHashMap<>(d.config());
    config.putAll(node.config());
    checkConfig(node, d);
    var compiled = new Graph.Node(node.id(), node.type(), config, node.bindings(), node.position());
    var result = implementation.evaluate(compiled, inputs, context);
    validateValues(node.id(), result.outputs(), d.outputs(), true);
    if (!result.waiting()
        && !(result.port() == null && d.ports().isEmpty())
        && (result.port() == null || !d.ports().contains(result.port())))
      throw new IllegalStateException("Node returned an undeclared control port: " + node.id());
    return result;
  }

  public void checkInputs(Graph.Node node, Map<String, Object> inputs) {
    validateValues(node.id(), inputs, handler(node.type()).descriptor().inputs(), false);
    handler(node.type()).validateLiveInput(node, inputs);
  }

  private void validateValues(
      String node, Map<String, Object> fields, Map<String, String> schema, boolean output) {
    if (fields == null) throw GraphIssue.bad(node, "inputs", "Expected an object of fields");
    for (String key : fields.keySet())
      if (!schema.containsKey(key) && !(output && "_usage".equals(key)))
        throw GraphIssue.bad(node, key, "Unknown field " + key);
    schema.forEach(
        (key, type) -> {
          var v = fields.get(key);
          boolean ok =
              switch (type) {
                case "TEXT" -> v instanceof String s && s.length() <= 32000;
                case "BOOLEAN" -> v instanceof Boolean;
                case "TASKS" -> validTasks(v);
                default -> false;
              };
          if (!ok) throw GraphIssue.bad(node, key, "Expected " + type + " in " + node + "." + key);
        });
  }

  private static boolean validTasks(Object value) {
    if (!(value instanceof List<?> list) || list.size() > 50) return false;
    var seen = new HashSet<String>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> m)
          || m.size() != 3
          || !(m.get("id") instanceof String id)
          || id.isBlank()
          || id.length() > 80
          || !seen.add(id)
          || !(m.get("title") instanceof String text)
          || text.isBlank()
          || text.length() > 500
          || !(m.get("assignee") instanceof String name)
          || name.length() > 100) return false;
    }
    return true;
  }

  private void checkConfig(Graph.Node node, NodeHandler.Descriptor d) {
    for (var e : node.config().entrySet()) {
      var f =
          d.configFields().stream()
              .filter(x -> x.key().equals(e.getKey()))
              .findFirst()
              .orElseThrow(
                  () -> GraphIssue.bad(node.id(), e.getKey(), "Unknown setting " + e.getKey()));
      var value = e.getValue();
      boolean valid =
          switch (f.type()) {
            case "text", "textarea" ->
                value instanceof String s && !s.isBlank() && s.length() <= 2000;
            case "boolean" -> value instanceof Boolean;
            case "number" ->
                value instanceof Number n
                    && Long.compare(n.longValue(), n.intValue()) == 0
                    && n.doubleValue() == n.intValue()
                    && (f.min() == null || n.intValue() >= f.min())
                    && (f.max() == null || n.intValue() <= f.max());
            case "select" -> value instanceof String s && f.options().contains(s);
            default -> false;
          };
      if (!valid)
        throw GraphIssue.bad(node.id(), e.getKey(), "Invalid " + e.getKey() + " on " + node.id());
    }
  }

  public void validateDraft(Graph graph) {
    validate(graph, false);
  }

  public void validate(Graph graph) {
    validate(graph, true);
  }

  private void validate(Graph graph, boolean complete) {
    if (graph == null
        || graph.nodes() == null
        || graph.edges() == null
        || graph.nodes().isEmpty()
        || graph.nodes().size() > 40
        || graph.edges().size() > 100)
      throw GraphIssue.bad(null, "graph", "Expected 1–40 nodes and at most 100 edges");
    Map<String, Graph.Node> byId = new LinkedHashMap<>();
    Map<String, List<Graph.Edge>> incoming = new HashMap<>(), outgoing = new HashMap<>();
    for (var n : graph.nodes()) {
      if (n == null
          || n.id() == null
          || !n.id().matches("[A-Za-z][A-Za-z0-9_-]{0,47}")
          || byId.putIfAbsent(n.id(), n) != null)
        throw GraphIssue.bad(
            n == null ? null : n.id(), "id", "Invalid or duplicate node identifier");
      if (n.position() != null
          && (!finitePosition(n.position().x()) || !finitePosition(n.position().y())))
        throw GraphIssue.bad(n.id(), "position", "Invalid node position");
      checkConfig(n, handler(n.type()).descriptor());
      incoming.put(n.id(), new ArrayList<>());
      outgoing.put(n.id(), new ArrayList<>());
    }
    for (var e : graph.edges()) {
      if (e == null || !byId.containsKey(e.from()) || !byId.containsKey(e.to()))
        throw GraphIssue.bad(null, "edge", "Unknown edge endpoint");
      var d = handler(byId.get(e.from()).type()).descriptor();
      if (e.port() == null
          || !d.ports().contains(e.port())
          || outgoing.get(e.from()).stream().anyMatch(x -> x.port().equals(e.port())))
        throw new GraphIssue(
            400,
            "Invalid or duplicate control port " + e.from() + "." + e.port(),
            e.from(),
            null,
            e.port());
      incoming.get(e.to()).add(e);
      outgoing.get(e.from()).add(e);
    }
    var ready = new ArrayDeque<String>();
    var pending = new HashMap<String, Integer>();
    incoming.forEach(
        (k, edges) -> {
          pending.put(k, edges.size());
          if (edges.isEmpty()) ready.add(k);
        });
    var start = graph.nodes().stream().filter(n -> "start".equals(n.type())).toList();
    if (complete && (start.size() != 1 || ready.size() != 1 || !ready.contains(start.get(0).id())))
      throw GraphIssue.bad(
          null, "graph", "A published graph requires one start and all nodes reachable from it");
    var dominators = new HashMap<String, Set<String>>();
    while (!ready.isEmpty()) {
      String id = ready.remove();
      var before = new HashSet<String>(byId.keySet());
      var predecessors = incoming.get(id);
      if (predecessors.isEmpty()) before.clear();
      for (var e : predecessors) before.retainAll(dominators.get(e.from()));
      var n = byId.get(id);
      var d = handler(n.type()).descriptor();
      for (var b : n.bindings().entrySet()) {
        String[] ref = b.getValue() == null ? new String[0] : b.getValue().split("\\.", -1);
        if ("start".equals(n.type())
            || ref.length != 2
            || !d.inputs().containsKey(b.getKey())
            || !byId.containsKey(ref[0])
            || !d.inputs()
                .get(b.getKey())
                .equals(handler(byId.get(ref[0]).type()).descriptor().outputs().get(ref[1]))
            || (complete && !before.contains(ref[0])))
          throw GraphIssue.bad(
              id, b.getKey(), "Invalid binding " + id + "." + b.getKey() + " from " + b.getValue());
      }
      if (complete) {
        if (!"start".equals(n.type()))
          for (var field : d.inputs().keySet())
            if (!n.bindings().containsKey(field))
              throw GraphIssue.bad(id, field, "Missing binding " + id + "." + field);
        for (String port : d.ports())
          if (outgoing.get(id).stream().noneMatch(e -> port.equals(e.port())))
            throw new GraphIssue(
                400, "Unconnected control port " + id + "." + port, id, null, port);
      }
      before.add(id);
      dominators.put(id, before);
      for (var e : outgoing.get(id))
        if (pending.merge(e.to(), -1, Integer::sum) == 0) ready.add(e.to());
    }
    if (dominators.size() != byId.size())
      throw GraphIssue.bad(null, "edges", "Control edges must form an acyclic graph");
  }

  private static boolean finitePosition(double n) {
    return Double.isFinite(n) && Math.abs(n) <= 1_000_000;
  }
}
