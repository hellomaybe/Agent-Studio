package studio.agent.graph;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import studio.agent.model.MeetingTaskExtractor;
import studio.agent.quality.ReleaseChecks;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:graphstudio;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
      "studio.live-daily-limit=1"
    })
class GraphStudioTest {
  @Autowired GraphCatalog catalog;
  @Autowired NodeRegistry registry;
  @Autowired GraphController controller;
  @Autowired org.springframework.jdbc.core.JdbcTemplate db;
  @Autowired LiveBudget budget;
  @MockitoBean MeetingTaskExtractor model;
  @MockitoBean ReleaseChecks checks;

  @Test
  void bothPublishedTemplatesDiscoverExecutableExtensionsAndFreezeCapabilities() {
    assertThat(catalog.apps())
        .extracting(GraphCatalog.Summary::id)
        .containsExactly("feedback-triage", "meeting-notes");
    for (var app : catalog.apps()) {
      var v = catalog.resolve(app.id(), 1L);
      registry.validate(v.graph());
      assertThat(v.capabilities()).containsEntry("start", "1").containsEntry("end", "1");
      assertThat(catalog.samples(app.id())).hasSizeGreaterThanOrEqualTo(2);
    }
    assertThat(registry.replaySafe("extract-tasks", "sample")).isTrue();
    assertThat(registry.replaySafe("extract-tasks", "live")).isFalse();
    var d =
        registry.descriptors().stream()
            .filter(x -> x.type().equals("filter-assigned"))
            .findFirst()
            .orElseThrow();
    var n = new Graph.Node("filter", "filter-assigned", Map.of(), Map.of());
    var r = registry.evaluate(n, d.exampleInputs(), new NodeHandler.Context("sample", true, null));
    assertThat((List<?>) r.outputs().get("items")).hasSize(1);
    assertThat((List<?>) r.outputs().get("excluded")).hasSize(1);
    assertThat(r.outputs().get("reason").toString()).contains("负责人为空");
    assertThat(r.effects()).isEmpty();
  }

  @Test
  void draftsUseCasVersionIsImmutableAndPublishIsIdempotent() {
    var old = catalog.resolve("feedback-triage", 1L);
    var nodes = new ArrayList<>(old.graph().nodes());
    var original = nodes.get(0);
    var moved =
        new Graph.Node(
            original.id(),
            original.type(),
            original.config(),
            original.bindings(),
            new Graph.Position(78, 340));
    nodes.set(0, moved);
    var g = new Graph(nodes, old.graph().edges());
    var saved = catalog.save(old.appId(), old.revision(), g);
    assertThat(saved.graph().nodes().get(0).position().x()).isEqualTo(78);
    assertThatThrownBy(() -> catalog.save(old.appId(), old.revision(), g))
        .isInstanceOfSatisfying(GraphIssue.class, e -> assertThat(e.status()).isEqualTo(409));
    var released = catalog.publish(old.appId(), saved.revision());
    assertThat(released.version()).isEqualTo(2L);
    verify(checks).requirePassing(old.appId(), saved.revision(), old.version(), g);
    assertThat(catalog.publish(old.appId(), saved.revision())).isEqualTo(released);
    assertThat(catalog.resolve(old.appId(), null)).isEqualTo(released);
    assertThat(catalog.resolve(old.appId(), 1L)).isEqualTo(old);
  }

  @Test
  void refusesCyclesMissingPortsAndBindingAvailableOnlyOnOneBranch() {
    var g = catalog.resolve("meeting-notes", 1L).graph();
    var edges = new ArrayList<>(g.edges());
    edges.remove(0);
    assertThatThrownBy(() -> registry.validate(new Graph(g.nodes(), edges)))
        .isInstanceOf(GraphIssue.class);
    edges.add(new Graph.Edge("start", "next", "start"));
    assertThatThrownBy(() -> registry.validateDraft(new Graph(g.nodes(), edges)))
        .isInstanceOf(GraphIssue.class);
    var n = new Graph.Node("join", "create-tasks", Map.of(), Map.of("items", "confirm.items"));
    var extra = new ArrayList<>(g.nodes());
    extra.add(n);
    var split = new ArrayList<>(g.edges());
    split.removeIf(e -> e.to().equals("empty") || e.to().equals("create"));
    split.add(new Graph.Edge("gate", "no", "join"));
    split.add(new Graph.Edge("confirm", "no", "empty"));
    split.add(new Graph.Edge("confirm", "yes", "join"));
    split.add(new Graph.Edge("join", "next", "create"));
    assertThatThrownBy(() -> registry.validate(new Graph(extra, split)))
        .isInstanceOfSatisfying(
            GraphIssue.class,
            x -> {
              assertThat(x.nodeId()).isEqualTo("join");
              assertThat(x.field()).isEqualTo("items");
            });
  }

  @Test
  void debugUsesOnlySelectedNodeAndNeverWritesBusinessObjects() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GraphErrors()).build();
    mvc.perform(get("/api/nodes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray());
    mvc.perform(get("/api/apps/meeting-notes/draft"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.liveEnabled").value(false));
    mvc.perform(
            post("/api/apps/meeting-notes/debug")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
{"revision":1,"nodeId":"create","mode":"sample","inputs":{"items":[{"id":"t","title":"Do it","assignee":""}]}}
"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.effects[0].kind").value("task"))
        .andExpect(jsonPath("$.data.nodeId").value("create"));
    mvc.perform(
            post("/api/apps/meeting-notes/debug")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"version":1,"nodeId":"filter","mode":"sample","inputs":{"items":"bad"}}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("items"))
        .andExpect(jsonPath("$.nodeId").value("filter"));
    verify(model, never()).extractAssigned(any(), any());
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void retiredCapabilityStillAllowsInspectionButPreventsReexecution() {
    db.update(
        "UPDATE studio_graph_version SET capabilities_json='{}' WHERE app_id='meeting-notes' AND"
            + " version_no=1");
    assertThat(catalog.readVersion("meeting-notes", 1L).graph().nodes()).isNotEmpty();
    assertThatThrownBy(() -> catalog.resolve("meeting-notes", 1L))
        .isInstanceOfSatisfying(GraphIssue.class, e -> assertThat(e.status()).isEqualTo(409));
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void liveDebugExecutesOnlyExplicitlySelectedModelOnceAndReturnsProviderUsage() throws Exception {
    db.update("DELETE FROM studio_live_reservation");
    when(model.available()).thenReturn(true);
    when(model.extractAssigned(eq("meeting"), anyString()))
        .thenReturn(
            new MeetingTaskExtractor.AssignedResult(
                List.of(new MeetingTaskExtractor.AssignedTask("Review", "Ada")),
                new MeetingTaskExtractor.Result(List.of(), "fixture-model", 8, 4)));
    var mvc =
        MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GraphErrors()).build();
    String body =
        """
{"version":1,"nodeId":"extract","mode":"live","inputs":{"text":"meeting"},"requestId":"same-click"}
""";
    mvc.perform(
            post("/api/apps/meeting-notes/debug")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outputs._usage.inputTokens").value(8))
        .andExpect(jsonPath("$.data.outputs.items[0].assignee").value("Ada"));
    mvc.perform(
            post("/api/apps/meeting-notes/debug")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
    verify(model, times(1)).extractAssigned(eq("meeting"), anyString());
  }

  @Test
  void sameLiveBudgetRejectsDuplicatesConcurrentRequestsAndExtraDailyUse() {
    when(model.available()).thenReturn(true);
    budget.reserve("workflow-a");
    assertThatThrownBy(() -> budget.reserve("debug:b"))
        .isInstanceOfSatisfying(GraphIssue.class, e -> assertThat(e.status()).isEqualTo(409));
    budget.finish("workflow-a");
    assertThatThrownBy(() -> budget.reserve("workflow-a"))
        .isInstanceOfSatisfying(GraphIssue.class, e -> assertThat(e.status()).isEqualTo(409));
    assertThatThrownBy(() -> budget.reserve("debug:b"))
        .isInstanceOfSatisfying(GraphIssue.class, e -> assertThat(e.status()).isEqualTo(429));
  }
}
