package studio.agent.meeting;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The first studio graph has fixed typed nodes, one optional transform and an editable condition.
 */
public final class MeetingGraph {
  private MeetingGraph() {}

  public record Config(Boolean sanitize, String mapping, String prompt, Integer threshold) {
    Config validated() {
      if (sanitize == null
          || mapping == null
          || !Set.of("start.transcript", "start.title", "sanitize.text").contains(mapping)
          || ("sanitize.text".equals(mapping) && !sanitize)
          || prompt == null
          || prompt.isBlank()
          || prompt.length() > 2000
          || threshold == null
          || threshold < 1
          || threshold > 3) {
        throw new Problem(400, "请检查输入映射、提示词和分支阈值。");
      }
      return this;
    }
  }

  public record Item(String id, String text) {}

  public record Preview(
      List<Item> items,
      List<String> path,
      String title,
      String transcript,
      String cleanedText,
      boolean eligible) {}

  record Sample(String title, String transcript, List<String> tasks) {}

  static Sample sample(String key) {
    if (key == null) throw new Problem(400, "请选择预设样例。");
    return switch (key) {
      case "weekly" ->
          new Sample("产品周会", "产品组整理上线清单。\n　测试组更新验收用例。", List.of("产品组整理上线清单", "测试组更新验收用例"));
      case "empty" -> new Sample("信息同步会", "各组已同步本周进展，本次没有新增待办。", List.of());
      case "ops" -> new Sample("运营复盘", "运营组提交活动复盘。", List.of("运营组提交活动复盘"));
      default -> throw new Problem(400, "找不到此预设样例。");
    };
  }

  static List<Item> items(List<String> tasks) {
    List<Item> result = new ArrayList<>();
    for (String task : tasks) result.add(new Item("task-" + (result.size() + 1), task));
    return List.copyOf(result);
  }

  static Preview preview(Config config, String key) {
    checkConfig(config);
    Sample sample = sample(key);
    List<Item> items = items("start.title".equals(config.mapping) ? List.of() : sample.tasks);
    boolean eligible = items.size() >= config.threshold;
    List<String> path = path(config, eligible);
    if (eligible) path = confirmedPath(path, true);
    return new Preview(
        items, path, sample.title, sample.transcript, clean(sample.transcript), eligible);
  }

  static Config checkConfig(Config config) {
    if (config == null) throw new Problem(400, "缺少流程配置。");
    return config.validated();
  }

  static List<String> path(Config config, boolean eligible) {
    List<String> path = new ArrayList<>();
    path.add("start");
    if (config.sanitize) path.add("sanitize");
    path.add("extract");
    path.add("gate");
    path.add(eligible ? "confirm" : "end");
    return List.copyOf(path);
  }

  static List<String> confirmedPath(List<String> before, boolean hasSelection) {
    List<String> path = new ArrayList<>(before);
    if (hasSelection) path.add("create");
    path.add("end");
    return List.copyOf(path);
  }

  static String clean(String text) {
    return text.replaceAll("(?U)[\\s\\uFEFF]+", " ").trim();
  }

  static String modelInput(Config config, String title, String transcript) {
    return switch (config.mapping) {
      case "start.title" -> title;
      case "sanitize.text" -> clean(transcript);
      default -> transcript;
    };
  }

  static final class Problem extends RuntimeException {
    final int status;

    Problem(int status, String message) {
      super(message);
      this.status = status;
    }
  }
}
