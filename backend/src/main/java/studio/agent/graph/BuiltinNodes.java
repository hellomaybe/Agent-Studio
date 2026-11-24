package studio.agent.graph;

import static studio.agent.graph.NodeHandler.*;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import studio.agent.model.MeetingTaskExtractor;

@Configuration(proxyBeanMethods = false)
class BuiltinNodes {
  private static final String TEXT = "TEXT", TASKS = "TASKS", BOOL = "BOOLEAN";
  private static final Map<String, Object> TASK =
      Map.of("id", "task-1", "title", "Send the release report", "assignee", "Alice");
  private static final List<String> NEXT = List.of("next"), BRANCH = List.of("yes", "no");
  private static final Field PROMPT =
      new Field("prompt", "提取要求", "textarea", List.of(), null, null);
  private static final Pattern ASSIGNED = Pattern.compile("^([^:：]{1,100})[:：]\\s*(.+)$");

  private static Descriptor spec(
      String type,
      String name,
      Map<String, String> in,
      Map<String, String> out,
      List<String> ports,
      Map<String, Object> defaults,
      List<Field> fields,
      Map<String, Object> sample) {
    return new Descriptor(type, name, "1", in, out, ports, defaults, fields, sample);
  }

  @FunctionalInterface
  private interface Execute {
    Outcome run(Graph.Node node, Map<String, Object> input, Context context);
  }

  private static NodeHandler node(Descriptor d, Execute action) {
    return node(d, (node, inputs) -> {}, action);
  }

  private static NodeHandler node(
      Descriptor d,
      java.util.function.BiConsumer<Graph.Node, Map<String, Object>> validateLive,
      Execute action) {
    return new NodeHandler() {
      public Descriptor descriptor() {
        return d;
      }

      public boolean replaySafe(String mode) {
        return !(d.liveModel() && "live".equals(mode));
      }

      public void validateLiveInput(Graph.Node n, Map<String, Object> inputs) {
        validateLive.accept(n, inputs);
      }

      public Outcome evaluate(Graph.Node n, Map<String, Object> i, Context c) {
        return action.run(n, i, c);
      }
    };
  }

  @Bean
  NodeHandler startNode() {
    return node(
        spec(
            "start",
            "输入文本",
            Map.of("text", TEXT),
            Map.of("text", TEXT),
            NEXT,
            Map.of(),
            List.of(),
            Map.of("text", "Alice: Send the release report")),
        (n, i, c) -> Outcome.next(Map.of("text", i.get("text"))));
  }

  @Bean
  NodeHandler extractNode(MeetingTaskExtractor model) {
    return node(
        spec(
                "extract-tasks",
                "提取待办与负责人",
                Map.of("text", TEXT),
                Map.of("items", TASKS),
                NEXT,
                Map.of("prompt", "保留明确的待办和负责人。"),
                List.of(PROMPT),
                Map.of("text", "Alice: Send the release report\n- Review dates"))
            .withLiveModel(),
        (n, inputs) -> {
          if (((String) inputs.get("text")).isBlank())
            throw GraphIssue.bad(n.id(), "text", "Please supply meeting text");
        },
        (n, i, c) -> {
          var text = (String) i.get("text");
          var tasks = new ArrayList<Map<String, Object>>();
          var output = new LinkedHashMap<String, Object>();
          if ("live".equals(c.mode())) {
            if (!model.available())
              throw new GraphIssue(503, "Live model is not configured", n.id(), null, null);
            var response = model.extractAssigned(text, (String) n.config().get("prompt"));
            for (var item : response.items()) addTask(tasks, item.title(), item.assignee());
            var usage = response.usage();
            var measured = new LinkedHashMap<String, Object>();
            measured.put("model", usage.model());
            if (usage.inputTokens() >= 0) measured.put("inputTokens", usage.inputTokens());
            if (usage.outputTokens() >= 0) measured.put("outputTokens", usage.outputTokens());
            output.put("_usage", measured);
          } else {
            // The sample fixture language is explicit: "owner: task" or "- unassigned task".
            for (String line : text.lines().toList()) {
              var task = line.strip();
              var match = ASSIGNED.matcher(task);
              if (match.matches()) addTask(tasks, match.group(2), match.group(1));
              else if (task.startsWith("- ") && !task.substring(2).isBlank())
                addTask(tasks, task.substring(2), "");
            }
          }
          output.put("items", List.copyOf(tasks));
          return Outcome.next(output);
        });
  }

  private static void addTask(List<Map<String, Object>> tasks, String title, String assignee) {
    if (tasks.size() >= 50 || title.isBlank() || title.length() > 500 || assignee.length() > 100)
      throw GraphIssue.bad(null, "items", "Task list exceeds the configured shape");
    tasks.add(
        Map.of(
            "id",
            "task-" + (tasks.size() + 1),
            "title",
            title.strip(),
            "assignee",
            assignee.strip()));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> tasks(Map<String, Object> i) {
    return (List<Map<String, Object>>) i.get("items");
  }

  @Bean
  NodeHandler conditionNode() {
    return node(
        spec(
            "condition",
            "条件分流",
            Map.of("value", BOOL),
            Map.of("value", BOOL),
            BRANCH,
            Map.of(),
            List.of(),
            Map.of("value", true)),
        (n, i, c) ->
            new Outcome(
                Map.of("value", i.get("value")),
                Boolean.TRUE.equals(i.get("value")) ? "yes" : "no",
                false,
                List.of()));
  }

  @Bean
  NodeHandler confirmNode() {
    return node(
        spec(
            "confirm",
            "人工确认",
            Map.of("items", TASKS),
            Map.of("items", TASKS),
            BRANCH,
            Map.of(),
            List.of(),
            Map.of("items", List.of(TASK))),
        (n, i, c) -> {
          var input = tasks(i);
          if (c.approvedIds() == null)
            return new Outcome(Map.of("items", input), null, true, List.of());
          var ids = new HashSet<>(c.approvedIds());
          if (ids.size() != c.approvedIds().size()
              || !input.stream().map(t -> (String) t.get("id")).toList().containsAll(ids))
            throw GraphIssue.bad(n.id(), "approvedIds", "Unknown or duplicate approval item");
          var chosen = input.stream().filter(t -> ids.contains(t.get("id"))).toList();
          return new Outcome(
              Map.of("items", chosen), chosen.isEmpty() ? "no" : "yes", false, List.of());
        });
  }

  @Bean
  NodeHandler createTasksNode() {
    return node(
        spec(
            "create-tasks",
            "创建已确认待办",
            Map.of("items", TASKS),
            Map.of("items", TASKS),
            NEXT,
            Map.of(),
            List.of(),
            Map.of("items", List.of(TASK))),
        (n, i, c) -> {
          var effects =
              tasks(i).stream()
                  .map(
                      t ->
                          Map.<String, Object>of(
                              "kind",
                              "task",
                              "title",
                              t.get("title"),
                              "assignee",
                              t.get("assignee"),
                              "id",
                              t.get("id")))
                  .toList();
          return new Outcome(Map.of("items", tasks(i)), "next", false, effects);
        });
  }

  @Bean
  NodeHandler classifyNode() {
    return node(
        spec(
            "classify-feedback",
            "按关键字分类客户反馈",
            Map.of("text", TEXT),
            Map.of("text", TEXT, "priority", TEXT, "urgent", BOOL, "items", TASKS),
            NEXT,
            Map.of("keywords", "urgent,blocked,outage,紧急,故障,无法"),
            List.of(new Field("keywords", "优先处理关键词（逗号分隔）", "textarea", List.of(), null, null)),
            Map.of("text", "Urgent: payments are blocked")),
        (n, i, c) -> {
          String text = (String) i.get("text");
          String lower = text.toLowerCase(Locale.ROOT);
          boolean urgent =
              Arrays.stream(((String) n.config().get("keywords")).split("[,，]"))
                  .map(String::strip)
                  .filter(w -> !w.isEmpty())
                  .anyMatch(w -> lower.contains(w.toLowerCase(Locale.ROOT)));
          var items = new ArrayList<Map<String, Object>>();
          if (urgent)
            addTask(items, text.strip().substring(0, Math.min(500, text.strip().length())), "");
          return Outcome.next(
              Map.of(
                  "text",
                  text,
                  "priority",
                  urgent ? "high" : "normal",
                  "urgent",
                  urgent,
                  "items",
                  items));
        });
  }

  @Bean
  NodeHandler ticketNode() {
    return node(
        spec(
            "create-ticket",
            "创建客户工单",
            Map.of("text", TEXT, "priority", TEXT),
            Map.of("message", TEXT),
            NEXT,
            Map.of(),
            List.of(),
            Map.of("text", "Payments are blocked", "priority", "high")),
        (n, i, c) -> {
          String text = ((String) i.get("text")).strip();
          if (text.isEmpty()) throw GraphIssue.bad(n.id(), "text", "Ticket title is empty");
          String title = text.substring(0, Math.min(text.length(), 500));
          return new Outcome(
              Map.of("message", title),
              "next",
              false,
              List.of(Map.of("kind", "ticket", "title", title, "priority", i.get("priority"))));
        });
  }

  @Bean
  NodeHandler endNode() {
    return node(
        spec(
            "end",
            "返回结果",
            Map.of(),
            Map.of("message", TEXT),
            List.of(),
            Map.of("message", "完成"),
            List.of(new Field("message", "输出说明", "text", List.of(), null, null)),
            Map.of()),
        (n, i, c) ->
            new Outcome(Map.of("message", n.config().get("message")), null, false, List.of()));
  }
}
