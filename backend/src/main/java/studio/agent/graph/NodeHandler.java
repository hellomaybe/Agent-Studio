package studio.agent.graph;

import java.util.List;
import java.util.Map;

/** Contribute a Spring bean to expose a node in the editor and executor. Effects are proposals. */
public interface NodeHandler {
  record Field(
      String key, String label, String type, List<String> options, Integer min, Integer max) {}

  record Descriptor(
      String type,
      String label,
      String version,
      Map<String, String> inputs,
      Map<String, String> outputs,
      List<String> ports,
      Map<String, Object> config,
      List<Field> configFields,
      Map<String, Object> exampleInputs,
      boolean liveModel) {
    public Descriptor(
        String type,
        String label,
        String version,
        Map<String, String> inputs,
        Map<String, String> outputs,
        List<String> ports,
        Map<String, Object> config,
        List<Field> configFields,
        Map<String, Object> exampleInputs) {
      this(
          type, label, version, inputs, outputs, ports, config, configFields, exampleInputs, false);
    }

    public Descriptor withLiveModel() {
      return new Descriptor(
          type, label, version, inputs, outputs, ports, config, configFields, exampleInputs, true);
    }
  }

  record Context(String mode, boolean preview, List<String> approvedIds) {}

  record Outcome(
      Map<String, Object> outputs,
      String port,
      boolean waiting,
      List<Map<String, Object>> effects) {
    public static Outcome next(Map<String, Object> output) {
      return new Outcome(output, "next", false, List.of());
    }
  }

  Descriptor descriptor();

  default void validateLiveInput(Graph.Node node, Map<String, Object> inputs) {}

  default boolean replaySafe(String mode) {
    return false;
  }

  Outcome evaluate(Graph.Node node, Map<String, Object> inputs, Context context);
}
