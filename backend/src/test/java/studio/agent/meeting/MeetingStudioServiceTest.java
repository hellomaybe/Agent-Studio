package studio.agent.meeting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import studio.agent.meeting.MeetingGraph.Config;
import studio.agent.meeting.MeetingGraph.Problem;
import studio.agent.meeting.MeetingStudioService.Confirm;
import studio.agent.meeting.MeetingStudioService.Save;
import studio.agent.meeting.MeetingStudioService.Start;
import studio.agent.model.MeetingTaskExtractor;

class MeetingStudioServiceTest {
  private MeetingTaskExtractor extractor;
  private MeetingStudioService studio;
  private TransactionTemplate tx;
  private JdbcTemplate db;
  private Runnable migrate;

  @TempDir Path directory;
  private String dbUrl;

  @BeforeEach
  void setup() {
    dbUrl =
        "jdbc:h2:file:"
            + directory.resolve("studio").toAbsolutePath()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
    var ds = new DriverManagerDataSource(dbUrl, "sa", "");
    db = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    migrate = () -> Flyway.configure().dataSource(ds).load().migrate();
    migrate.run();
    extractor = mock(MeetingTaskExtractor.class);
    studio = new MeetingStudioService(db, tx, extractor, false, 5);
  }

  private Start sample(String key, long rev, String id) {
    return new Start("sample", key, null, null, id, rev);
  }

  private Start live(String id) {
    return new Start("live", null, "项目周会", "private transcript in test", id, 1);
  }

  @Test
  void revisionAndMigrationDoNotChangePersistedSnapshot() {
    var initialRun = studio.start(sample("weekly", 1, "a"));
    var config = new Config(true, "sanitize.text", "总结任务", 2);
    assertEquals(2, studio.save(new Save(1, config)).revision());
    assertEquals(409, assertThrows(Problem.class, () -> studio.save(new Save(1, config))).status);
    migrate.run();
    var reopened = new DriverManagerDataSource(dbUrl, "sa", "");
    studio =
        new MeetingStudioService(
            new JdbcTemplate(reopened),
            new TransactionTemplate(new DataSourceTransactionManager(reopened)),
            extractor,
            false,
            5);
    assertEquals(config, studio.draft().config());
    assertEquals(1, studio.get(initialRun.runId()).revision());
    assertFalse(studio.get(initialRun.runId()).path().contains("sanitize"));
    var thresholdSkip = studio.start(sample("ops", 2, "b"));
    assertEquals(List.of("start", "sanitize", "extract", "gate", "end"), thresholdSkip.path());
    assertEquals(1, thresholdSkip.items().size());
    verifyNoInteractions(extractor);
  }

  @Test
  void confirmationIsValidatedIdempotentAndRespectsSelectedPath() {
    var request = sample("weekly", 1, "a");
    var before = studio.start(request);
    assertEquals(before, studio.start(sample("weekly", 1, "A")));
    assertEquals("waiting", before.status());
    assertEquals(
        409, assertThrows(Problem.class, () -> studio.start(sample("empty", 1, "a"))).status);
    assertEquals(
        404,
        assertThrows(Problem.class, () -> studio.confirm("missing", new Confirm(List.of())))
            .status);
    assertEquals(404, assertThrows(Problem.class, () -> studio.get("missing")).status);
    assertEquals(1, studio.list().size());
    assertEquals(
        400,
        assertThrows(
                Problem.class, () -> studio.confirm(before.runId(), new Confirm(List.of("bad"))))
            .status);
    var selected = new Confirm(List.of("task-2"));
    var after = studio.confirm(before.runId(), selected);
    assertEquals(List.of("start", "extract", "gate", "confirm", "create", "end"), after.path());
    assertEquals(after, studio.confirm(before.runId(), selected));
    assertEquals(after, studio.start(request));
    assertEquals(
        409,
        assertThrows(Problem.class, () -> studio.confirm(before.runId(), new Confirm(List.of())))
            .status);
    assertEquals(1, studio.list().size());
    var emptySelection = studio.start(sample("ops", 1, "b"));
    assertFalse(
        studio.confirm(emptySelection.runId(), new Confirm(List.of())).path().contains("create"));
  }

  @Test
  void previewOnlyAcceptsTypedMappingsAndFixedFixtures() {
    var config = studio.draft().config();
    assertTrue(MeetingGraph.preview(config, "weekly").eligible());
    assertEquals("A B", MeetingGraph.clean("\uFEFF　A\u00a0\t\nB　"));
    var preview = MeetingGraph.preview(new Config(true, "sanitize.text", "测试", 1), "weekly");
    assertEquals(
        MeetingGraph.modelInput(
            new Config(true, "sanitize.text", "测试", 1), preview.title(), preview.transcript()),
        preview.cleanedText());
    assertEquals(
        List.of("start", "extract", "gate", "end"), MeetingGraph.preview(config, "empty").path());
    assertFalse(MeetingGraph.preview(new Config(false, "start.title", "测试", 1), "ops").eligible());
    assertThrows(
        Problem.class,
        () -> MeetingGraph.preview(new Config(false, "sanitize.text", "测试", 1), "weekly"));
    assertThrows(Problem.class, () -> MeetingGraph.preview(config, "custom"));
    assertThrows(
        Problem.class, () -> studio.start(new Start("sample", "weekly", "自定义", null, "a", 1)));
    assertFalse(studio.draft().liveEnabled());
    assertEquals(403, assertThrows(Problem.class, () -> studio.start(live("b"))).status);
    assertTrue(studio.list().isEmpty());
    verifyNoInteractions(extractor);
  }

  @Test
  void paidCallsReserveBeforeDispatchAndRetriesCannotSubmitTwice() throws Exception {
    when(extractor.available()).thenReturn(true);
    studio = new MeetingStudioService(db, tx, extractor, true, 1);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(extractor.extract(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              assertTrue(release.await(10, TimeUnit.SECONDS));
              return new MeetingTaskExtractor.Result(List.of("写纪要"), "fixture", 15, 5);
            });
    var pool = Executors.newSingleThreadExecutor();
    try {
      var live = live("requestA");
      var pending = pool.submit(() -> studio.start(live));
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      var receipt = studio.start(live);
      assertEquals("running", receipt.status());
      assertEquals(409, assertThrows(Problem.class, () -> studio.start(live("concurrent"))).status);
      release.countDown();
      var complete = pending.get(10, TimeUnit.SECONDS);
      assertEquals(receipt.runId(), complete.runId());
      assertEquals(15L, complete.usage().inputTokens());
      assertEquals("waiting", complete.status());
      assertEquals(complete, studio.start(live));
      assertEquals(429, assertThrows(Problem.class, () -> studio.start(live("other"))).status);
      assertFalse(
          db.queryForObject("SELECT state_json FROM studio_run", String.class)
              .contains(live.transcript()));
      verify(extractor).extract(eq(live.transcript()), anyString());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void lostModelWorkerLeavesAReceiptAndRecoveryNeverResubmits() {
    when(extractor.available()).thenReturn(true);
    studio = new MeetingStudioService(db, tx, extractor, true, 5);
    when(extractor.extract(anyString(), anyString())).thenThrow(new AssertionError("stop worker"));
    var request = live("interrupt-1");
    assertThrows(AssertionError.class, () -> studio.start(request));
    assertEquals("running", studio.start(request).status());
    db.update(
        "UPDATE studio_run SET created_at=? WHERE request_id=?",
        java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofHours(2))),
        request.requestId());
    var recovered = studio.start(request);
    assertEquals("failed", recovered.status());
    assertEquals(recovered, studio.get(recovered.runId()));
    verify(extractor).extract(anyString(), anyString());
  }

  @Test
  void providerFailureIsRecordedOnceWithSafeMessage() {
    when(extractor.available()).thenReturn(true);
    studio = new MeetingStudioService(db, tx, extractor, true, 2);
    when(extractor.extract(anyString(), anyString()))
        .thenThrow(new IllegalStateException("private example"));
    var request = live("x");
    var failed = studio.start(request);
    assertEquals("failed", failed.status());
    assertFalse(failed.error().contains("private"));
    assertEquals(failed, studio.start(request));
    verify(extractor).extract(anyString(), anyString());
  }
}
