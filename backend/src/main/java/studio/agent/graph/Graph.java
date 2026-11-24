package studio.agent.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Graph(List<Node> nodes, List<Edge> edges) {
  public Graph {
    nodes = nodes == null ? null : Collections.unmodifiableList(new java.util.ArrayList<>(nodes));
    edges = edges == null ? null : Collections.unmodifiableList(new java.util.ArrayList<>(edges));
  }

  public record Node(
      String id,
      String type,
      Map<String, Object> config,
      Map<String, String> bindings,
      Position position) {
    public Node {
      config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
      bindings =
          bindings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    }

    public Node(String id, String type, Map<String, Object> config, Map<String, String> bindings) {
      this(id, type, config, bindings, null);
    }
  }

  public record Edge(String from, String port, String to) {}

  public record Position(double x, double y) {}
}
