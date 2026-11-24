package studio.agent.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomModelNodeTest {
  @Test
  void modelExtensionsCanDeclareTheirOwnInputFields() {
    var handler =
        new NodeHandler() {
          public Descriptor descriptor() {
            return new Descriptor(
                    "question-agent",
                    "Question",
                    "1",
                    Map.of("question", "TEXT"),
                    Map.of("answer", "TEXT"),
                    List.of("next"),
                    Map.of(),
                    List.of(),
                    Map.of("question", "What happens next?"))
                .withLiveModel();
          }

          public Outcome evaluate(Graph.Node node, Map<String, Object> in, Context context) {
            return Outcome.next(Map.of("answer", in.get("question")));
          }
        };
    var graph =
        new Graph(List.of(new Graph.Node("ask", "question-agent", Map.of(), Map.of())), List.of());
    var catalog = mock(GraphCatalog.class);
    when(catalog.resolve("extension", 1L))
        .thenReturn(
            new GraphCatalog.Version("extension", 1, 1, graph, Map.of("question-agent", "1")));
    var budget = mock(LiveBudget.class);
    var controller = new GraphController(catalog, new NodeRegistry(List.of(handler)), budget);
    var result =
        controller.debug(
            "extension",
            new GraphController.Debug(
                null, 1L, null, "ask", "live", Map.of("question", "A question"), "test-click"));
    assertThat(result.data().outputs()).containsEntry("answer", "A question");
    verify(budget).reserve("debug:extension:test-click");
    verify(budget).finish("debug:extension:test-click");
  }
}
