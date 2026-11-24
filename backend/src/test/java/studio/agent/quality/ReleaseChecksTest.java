package studio.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import studio.agent.graph.Graph;
import studio.agent.graph.GraphCatalog;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:quality;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
      "studio.workflow.scheduler-enabled=false"
    })
@Transactional
class ReleaseChecksTest {
  @Autowired GraphCatalog catalog;
  @Autowired ReleaseChecks checks;
  @Autowired EvaluationController evaluation;

  @Test
  void changingAssertionsInvalidatesAnOtherwiseIdenticalRelease() {
    String id = "feedback-triage";
    var draft = catalog.draft(id);
    var saved = catalog.save(id, draft.revision(), draft.graph());
    var report = evaluate(id, saved);
    assertThat(report.cases()).hasSize(3).allMatch(c -> c.candidate().pass());
    var suite = checks.suite(id);
    checks.save(
        id,
        new ReleaseChecks.Suite(
            suite.revision(),
            List.of(
                new ReleaseChecks.Fixture("普通反馈无需审核", "Please add a dark theme", true, false, 0))));
    assertThatThrownBy(() -> catalog.publish(id, saved.revision()))
        .isInstanceOf(ResponseStatusException.class);
    var next = evaluate(id, saved);
    assertThat(next.fingerprint()).isNotEqualTo(report.fingerprint());
    assertThat(catalog.publish(id, saved.revision()).version()).isEqualTo(2);
  }

  @Test
  void comparingUnsavedNodesDoesNotAuthorizeTheSavedGraph() {
    String id = "meeting-notes";
    var initial = catalog.draft(id);
    var draft = catalog.save(id, initial.revision(), initial.graph());
    var alternative =
        new Graph(
            draft.graph().nodes().stream()
                .map(
                    n ->
                        n.id().equals("end")
                            ? new Graph.Node(
                                n.id(),
                                n.type(),
                                Map.of("message", "unsaved output"),
                                n.bindings(),
                                n.position())
                            : n)
                .toList(),
            draft.graph().edges());
    var report =
        (EvaluationController.Report)
            evaluation
                .evaluate(id, new EvaluationController.Request(draft.revision(), alternative))
                .get("data");
    assertThat(report.cases()).allMatch(c -> c.candidate().pass());
    assertThatThrownBy(() -> catalog.publish(id, draft.revision()))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void failedFixtureIdentifiesTheBehaviorThatDidNotMatch() {
    String id = "meeting-notes";
    var suite = checks.suite(id);
    checks.save(
        id,
        new ReleaseChecks.Suite(
            suite.revision(),
            List.of(new ReleaseChecks.Fixture("拒绝多报", "No action", true, true, 1))));
    var result = evaluate(id, catalog.draft(id)).cases().get(0).candidate();
    assertThat(result.pass()).isFalse();
    assertThat(result.detail()).contains("直接结束", "0");
  }

  private EvaluationController.Report evaluate(String id, GraphCatalog.Draft draft) {
    return (EvaluationController.Report)
        evaluation
            .evaluate(id, new EvaluationController.Request(draft.revision(), draft.graph()))
            .get("data");
  }

  @Test
  void aCorrectPathCannotHideAnIncorrectClassifiedValue() {
    String id = "feedback-triage";
    var suite = checks.suite(id);
    checks.save(
        id,
        new ReleaseChecks.Suite(
            suite.revision(),
            List.of(
                new ReleaseChecks.Fixture(
                    "检查分类字段",
                    "Urgent: payment blocked",
                    true,
                    true,
                    1,
                    "classify.priority",
                    "normal"))));
    var result = evaluate(id, catalog.draft(id)).cases().get(0).candidate();
    assertThat(result.pass()).isFalse();
    assertThat(result.detail()).contains("classify.priority = high");
  }
}
