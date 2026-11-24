package studio.agent.extensions;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import studio.agent.graph.Graph;
import studio.agent.graph.NodeHandler;

/** A standalone Spring bean contributes both the executable node and its editor form. */
@Component
public class FilterAssignedNode implements NodeHandler {
  @Override
  public Descriptor descriptor() {
    return new Descriptor(
        "filter-assigned",
        "筛选已分配待办",
        "1",
        Map.of("items", "TASKS"),
        Map.of("items", "TASKS", "hasItems", "BOOLEAN", "excluded", "TASKS", "reason", "TEXT"),
        List.of("next"),
        Map.of("onlyAssigned", true, "minCount", 1),
        List.of(
            new Field("onlyAssigned", "仅保留已有负责人", "boolean", List.of(), null, null),
            new Field("minCount", "进入确认的最少条数", "number", List.of(), 1, 50)),
        Map.of(
            "items",
            List.of(
                Map.of("id", "task-1", "title", "Send report", "assignee", "Alice"),
                Map.of("id", "task-2", "title", "Review dates", "assignee", ""))));
  }

  @Override
  public boolean replaySafe(String mode) {
    return true;
  }

  @Override
  public Outcome evaluate(Graph.Node node, Map<String, Object> inputs, Context context) {
    @SuppressWarnings("unchecked")
    var items = (List<Map<String, Object>>) inputs.get("items");
    boolean assigned = (Boolean) node.config().get("onlyAssigned");
    int min = ((Number) node.config().get("minCount")).intValue();
    var selected =
        items.stream().filter(t -> !assigned || !((String) t.get("assignee")).isBlank()).toList();
    var excluded =
        items.stream().filter(t -> assigned && ((String) t.get("assignee")).isBlank()).toList();
    return Outcome.next(
        Map.of(
            "items",
            selected,
            "hasItems",
            selected.size() >= min,
            "excluded",
            excluded,
            "reason",
            excluded.isEmpty() ? "没有排除的待办" : "已排除 " + excluded.size() + " 条负责人为空的待办"));
  }
}
