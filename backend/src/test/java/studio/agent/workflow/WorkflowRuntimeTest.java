package studio.agent.workflow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import studio.agent.graph.*;
import studio.agent.workflow.WorkflowRunService.*;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:workflowtests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "studio.workflow.scheduler-enabled=false",
      "studio.live-enabled=false"
    })
class WorkflowRuntimeTest {
  @Autowired WorkflowRunService service;
  @Autowired JdbcTemplate db;
  @Autowired TransactionTemplate tx;
  @Autowired GraphCatalog catalog;
  @Autowired NodeRegistry registry;
  @Autowired LiveBudget budget;

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private static Start sample(String text, String pauseAt) {
    return new Start(1L, key(), "sample", Map.of("text", text), null, pauseAt);
  }

  private static Signal confirm(View row, List<String> ids) {
    return new Signal(key(), row.waitPrompt().waitId(), Map.of("selectedIds", ids));
  }

  private View advance(WorkflowRunService worker, View initial) {
    for (int i = 0; i < 35; i++) {
      var row = worker.get(initial.appId(), initial.runId());
      if (!Set.of("queued", "running").contains(row.status())) return row;
      worker.tick();
    }
    throw new AssertionError("did not reach a checkpoint");
  }

  @Test
  void approvalSurvivesRecreatedRuntimeAndEffectsCommitOnlyOnce() {
    Start request = sample("Alice: Send the launch report\n- Not assigned", null);
    View first = service.start("meeting-notes", request);
    assertEquals(first.runId(), service.start("meeting-notes", request).runId());
    assertEquals(
        409,
        assertThrows(
                Fault.class,
                () ->
                    service.start(
                        "meeting-notes",
                        new Start(
                            1L,
                            request.requestId(),
                            "sample",
                            Map.of("text", "another request"),
                            null,
                            null)))
            .status);
    assertEquals(
        404, assertThrows(Fault.class, () -> service.get("feedback-triage", first.runId())).status);
    View waiting = advance(service, first);
    assertEquals("waiting", waiting.status());
    assertEquals(1, waiting.waitPrompt().items().size());
    assertTrue(waiting.effects().isEmpty());
    var input = new DebugRequest("extract", true);
    assertEquals(
        Map.of("text", "Alice: Send the launch report\n- Not assigned"),
        service.debugInput(waiting.appId(), waiting.runId(), input).inputs());
    assertEquals(
        400,
        assertThrows(
                Fault.class,
                () ->
                    service.debugInput(
                        waiting.appId(), waiting.runId(), new DebugRequest("extract", false)))
            .status);
    assertEquals(
        409,
        assertThrows(
                Fault.class,
                () ->
                    service.debugInput(
                        waiting.appId(), waiting.runId(), new DebugRequest("create", true)))
            .status);
    assertEquals(waiting.eventCount(), service.get(waiting.appId(), waiting.runId()).eventCount());
    var changedCatalog = mock(GraphCatalog.class);
    var newWorker = new WorkflowRunService(db, tx, changedCatalog, registry, budget);
    String id = waiting.waitPrompt().items().get(0).get("id").toString();
    var signal = confirm(waiting, List.of(id));
    var queued = newWorker.signal(waiting.appId(), waiting.runId(), "resume", signal);
    assertEquals("queued", queued.status());
    var done = advance(newWorker, queued);
    assertEquals("completed", done.status());
    assertEquals("task", done.effects().get(0).get("kind"));
    assertEquals(1, done.effects().size());
    assertEquals(
        done.effects(), newWorker.signal(done.appId(), done.runId(), "resume", signal).effects());
    assertEquals(
        409,
        assertThrows(
                Fault.class,
                () ->
                    newWorker.signal(
                        done.appId(),
                        done.runId(),
                        "resume",
                        new Signal(
                            signal.signalId(), signal.waitId(), Map.of("selectedIds", List.of()))))
            .status);
    assertFalse(WorkflowRunService.json(done.events()).contains("launch report"));
    assertNotNull(done.graph());
    assertFalse(done.capabilityVersions().isEmpty());
    assertEquals(1, done.versionId());
    for (int i = 0; i < done.events().size(); i++) assertEquals(i + 1, done.events().get(i).seq());
    assertTrue(
        done.events().stream()
            .anyMatch(e -> "node.completed".equals(e.type()) && e.elapsedMs() != null));
    assertTrue(done.events().stream().allMatch(e -> e.usage() == null));
    assertTrue(
        done.events().stream()
            .anyMatch(e -> "yes".equals(e.port()) && "create".equals(e.nextNodeId())));
    verifyNoInteractions(changedCatalog);
    var resumed =
        done.events().stream()
            .filter(e -> e.type().equals("signal.resume"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        java.time.Duration.between(
                Instant.parse(waiting.waitPrompt().since()), Instant.parse(resumed.occurredAt()))
            .toMillis(),
        resumed.waitMs());
    assertNull(resumed.elapsedMs());
  }

  @Test
  void feedbackHasRealLocalTicketOnlyOnApprovedBranchAndPauseHonorsBoundaries() {
    View first = service.start("feedback-triage", sample("Urgent: payments blocked", "start"));
    View paused = advance(service, first);
    assertEquals("paused", paused.status());
    assertEquals(List.of("start"), paused.path());
    var continueSignal = new Signal(key(), null, null);
    View waiting =
        advance(
            service, service.signal(paused.appId(), paused.runId(), "continue", continueSignal));
    assertEquals("waiting", waiting.status());
    assertEquals("review", waiting.currentNodeId());
    var result =
        advance(
            service,
            service.signal(
                first.appId(),
                first.runId(),
                "resume",
                confirm(
                    waiting, List.of(waiting.waitPrompt().items().get(0).get("id").toString()))));
    assertEquals("completed", result.status());
    assertEquals("ticket", result.effects().get(0).get("kind"));
    var low =
        advance(service, service.start("feedback-triage", sample("Thanks for the update", null)));
    assertEquals("completed", low.status());
    assertTrue(low.path().contains("ignore"));
    assertTrue(low.effects().isEmpty());
  }

  @Test
  void duplicateConcurrentSignalsDoNotEnqueueOrCreateTwice() throws Exception {
    var run = advance(service, service.start("meeting-notes", sample("Kai: Ship a patch", null)));
    var decision = confirm(run, List.of(run.waitPrompt().items().get(0).get("id").toString()));
    var pool = Executors.newFixedThreadPool(2);
    try {
      var gate = new CountDownLatch(1);
      Callable<View> op =
          () -> {
            gate.await(5, TimeUnit.SECONDS);
            return service.signal(run.appId(), run.runId(), "resume", decision);
          };
      var one = pool.submit(op);
      var two = pool.submit(op);
      gate.countDown();
      one.get(8, TimeUnit.SECONDS);
      two.get(8, TimeUnit.SECONDS);
      var done = advance(service, run);
      assertEquals(1, done.effects().size());
      assertEquals(1, done.events().stream().filter(e -> "signal.resume".equals(e.type())).count());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void capabilityChangesFailExplicitlyInsteadOfUsingANewImplementation() {
    var started = service.start("meeting-notes", sample("Alice: Release notes", null));
    var changed = spy(registry);
    doReturn("new-version").when(changed).version("start");
    var worker = new WorkflowRunService(db, tx, catalog, changed, budget);
    var failed = advance(worker, started);
    assertEquals("failed", failed.status());
    assertEquals("capability_version_unavailable", failed.errorCode());
    assertTrue(failed.effects().isEmpty());
  }

  @Test
  void paginatedEventsAndLightweightSummariesPreserveHistoryWithoutCurrentContent() {
    var run = service.start("feedback-triage", sample("Only in source input", null));
    for (int i = 0; i < 51; i++) {
      service.signal(run.appId(), run.runId(), "pause", new Signal(key(), null, null));
      service.signal(run.appId(), run.runId(), "continue", new Signal(key(), null, null));
    }
    var detail = service.get(run.appId(), run.runId());
    assertTrue(detail.eventsTruncated());
    assertEquals(100, detail.events().size());
    assertEquals(103, detail.eventCount());
    var first = service.events(run.appId(), run.runId(), 0, 55);
    assertTrue(first.hasMore());
    assertEquals(1, first.events().get(0).seq());
    assertEquals(55, first.nextCursor());
    var rest = service.events(run.appId(), run.runId(), first.nextCursor(), 55);
    assertFalse(rest.hasMore());
    assertEquals(103, rest.nextCursor());
    assertEquals(48, rest.events().size());
    assertEquals(
        400,
        assertThrows(Fault.class, () -> service.events(run.appId(), run.runId(), 0, 201)).status);
    assertEquals(
        404,
        assertThrows(Fault.class, () -> service.events("meeting-notes", run.runId(), 0, 1)).status);
    var summary =
        service.list(run.appId()).stream()
            .filter(x -> x.runId().equals(run.runId()))
            .findFirst()
            .orElseThrow();
    assertEquals(1, summary.versionId());
    assertFalse(summary.title().isBlank());
    assertEquals("queued", summary.status());
    assertFalse(WorkflowRunService.json(summary).contains("Only in source"));
    db.update(
        "UPDATE workflow_run SET graph_version=NULL, run_mode=NULL WHERE run_id=?", run.runId());
    assertTrue(service.list(run.appId()).contains(summary));
    advance(service, run);
  }

  @Test
  void invalidEffectFailsBeforeAnyLocalWriteAndUnknownUsageStaysAbsent() {
    var nodes = spy(registry);
    doReturn(
            new NodeHandler.Outcome(
                Map.of(
                    "message",
                    "finished",
                    "_usage",
                    Map.of("inputTokens", -1, "outputTokens", 2.5)),
                null,
                false,
                List.of(Map.of("kind", "task", "title", "first"), Map.of("bad", "data"))))
        .when(nodes)
        .evaluate(argThat(n -> n != null && "end".equals(n.type())), anyMap(), any());
    var worker = new WorkflowRunService(db, tx, catalog, nodes, budget);
    var run = advance(worker, worker.start("feedback-triage", sample("positive review", null)));
    assertEquals("failed", run.status());
    assertEquals("node_result_invalid", run.errorCode());
    assertTrue(run.effects().isEmpty());
    assertTrue(run.events().stream().allMatch(e -> e.usage() == null));
  }

  @Test
  void liveDisabledFailsAdmissionWithoutClaimingPaidAttempt() {
    var run =
        service.start(
            "meeting-notes",
            new Start(1L, key(), "live", Map.of("text", "Sample meeting"), true, null));
    var refused = advance(service, run);
    assertEquals("failed", refused.status());
    assertEquals("live_unavailable", refused.errorCode());
    assertFalse(
        refused.events().stream()
            .anyMatch(e -> "extract".equals(e.nodeId()) && "node.started".equals(e.type())));
  }

  @Test
  void retryUsesSameOperationKeyWithANewAttemptAfterKnownSafeFailure() {
    var nodes = spy(registry);
    doThrow(new IllegalStateException("transient local problem"))
        .doCallRealMethod()
        .when(nodes)
        .evaluate(argThat(n -> n != null && "start".equals(n.type())), anyMap(), any());
    var worker = new WorkflowRunService(db, tx, catalog, nodes, budget);
    var failed =
        advance(worker, worker.start("feedback-triage", sample("ordinary feedback", null)));
    assertEquals("failed", failed.status());
    assertTrue(failed.canRetry());
    var retry = new Signal(key(), null, null);
    var queued = worker.signal(failed.appId(), failed.runId(), "retry", retry);
    assertEquals("queued", queued.status());
    var done = advance(worker, queued);
    assertEquals("completed", done.status());
    assertEquals("completed", worker.signal(done.appId(), done.runId(), "retry", retry).status());
    var attempts =
        done.events().stream()
            .filter(e -> "start".equals(e.nodeId()) && "node.started".equals(e.type()))
            .toList();
    assertEquals(2, attempts.size());
    assertEquals(attempts.get(0).operationKey(), attempts.get(1).operationKey());
    assertEquals(1, attempts.get(0).attemptNo());
    assertEquals(2, attempts.get(1).attemptNo());
    assertEquals(List.of("text"), attempts.get(1).inputFields());
  }

  @Test
  void abandonedExternalAttemptStopsWithoutReissuingAndLivePayloadExpires() {
    var nodeSpy = spy(registry);
    LiveBudget budgetSpy = mock(LiveBudget.class);
    var testWorker = new WorkflowRunService(db, tx, catalog, nodeSpy, budgetSpy);
    String source = "Confidential input for retention test";
    assertEquals(
        400,
        assertThrows(
                Fault.class,
                () ->
                    testWorker.start(
                        "meeting-notes",
                        new Start(1L, key(), "live", Map.of("text", source), false, null)))
            .status);
    var run =
        testWorker.start(
            "meeting-notes", new Start(1L, key(), "live", Map.of("text", source), true, null));
    doThrow(new AssertionError("simulated process loss"))
        .when(nodeSpy)
        .evaluate(argThat(n -> n != null && "extract-tasks".equals(n.type())), anyMap(), any());
    assertThrows(AssertionError.class, () -> advance(testWorker, run));
    JsonMapper json = JsonMapper.builder().build();
    var before =
        json.readValue(
            db.queryForObject(
                "SELECT state_json FROM workflow_run WHERE run_id=?", String.class, run.runId()),
            Checkpoint.class);
    var old = Instant.now().minusSeconds(400);
    before.startedAt = old.toString();
    db.update(
        "UPDATE workflow_run SET lease_until=?,state_json=? WHERE run_id=?",
        Timestamp.from(old),
        json.writeValueAsString(before),
        run.runId());
    testWorker.tick();
    assertEquals("unknown", testWorker.get(run.appId(), run.runId()).status());
    verify(nodeSpy, times(1))
        .evaluate(argThat(n -> n != null && "extract-tasks".equals(n.type())), anyMap(), any());
    before =
        json.readValue(
            db.queryForObject(
                "SELECT state_json FROM workflow_run WHERE run_id=?", String.class, run.runId()),
            Checkpoint.class);
    before.expiresAt = old.toString();
    db.update(
        "UPDATE workflow_run SET expires_at=?,state_json=? WHERE run_id=?",
        Timestamp.from(old),
        json.writeValueAsString(before),
        run.runId());
    var expired = testWorker.get(run.appId(), run.runId());
    assertTrue(expired.inputKeys().isEmpty());
    assertEquals(
        410,
        assertThrows(
                Fault.class,
                () ->
                    testWorker.debugInput(
                        expired.appId(), expired.runId(), new DebugRequest("start", true)))
            .status);
    assertFalse(
        db.queryForObject(
                "SELECT state_json FROM workflow_run WHERE run_id=?", String.class, run.runId())
            .contains(source));
    assertFalse(expired.canRetry());
    assertFalse(expired.canReinput());
    var fresh = new Reinput(key(), Map.of("text", "Replacement data"), true);
    assertEquals(
        409,
        assertThrows(Fault.class, () -> testWorker.reinput(run.appId(), run.runId(), fresh))
            .status);
    var verify = new Review(key(), "verified", "receipt_123");
    var checked = testWorker.review(run.appId(), run.runId(), verify);
    assertTrue(checked.reviewed());
    assertEquals("unknown", checked.status());
    assertEquals(64, checked.receiptFingerprint().length());
    assertFalse(WorkflowRunService.json(checked).contains("receipt_123"));
    assertEquals(checked.events(), testWorker.review(run.appId(), run.runId(), verify).events());
    var oldRecord =
        testWorker.review(run.appId(), run.runId(), new Review(key(), "terminate", null));
    assertEquals("terminated", oldRecord.status());
    assertTrue(oldRecord.canReinput());
    var child = testWorker.reinput(run.appId(), run.runId(), fresh);
    assertNotEquals(run.runId(), child.runId());
    assertEquals(run.runId(), child.parentRunId());
    assertEquals(child.runId(), testWorker.reinput(run.appId(), run.runId(), fresh).runId());
    var parent = testWorker.get(run.appId(), run.runId());
    assertEquals("terminated", parent.status());
    assertTrue(parent.events().stream().anyMatch(e -> child.runId().equals(e.relatedRunId())));
    assertEquals("failed", advance(service, child).status());
  }
}
