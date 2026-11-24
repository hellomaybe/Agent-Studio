package studio.agent.graph;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import studio.agent.meeting.MeetingStudioService;
import studio.agent.model.MeetingTaskExtractor;

class LegacyLiveBudgetTest {
  @Test
  void v2HistoryMigratesAndAllLiveRoutesShareOneLedger() {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:priorlive;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    if (System.getenv("STUDIO_TEST_BUDGET_DB_URL") != null) {
      ds =
          new DriverManagerDataSource(
              System.getenv("STUDIO_TEST_BUDGET_DB_URL"),
              System.getenv("STUDIO_TEST_BUDGET_DB_USER"),
              System.getenv("STUDIO_TEST_BUDGET_DB_PASSWORD"));
      assertThat(
              new JdbcTemplate(ds)
                  .queryForObject(
                      "SELECT COUNT(*) FROM information_schema.tables WHERE"
                          + " table_schema=DATABASE()",
                      Integer.class))
          .as("The optional MySQL test requires an empty disposable database")
          .isZero();
    }
    Flyway.configure().dataSource(ds).target("2").load().migrate();
    var db = new JdbcTemplate(ds);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    var model = mock(MeetingTaskExtractor.class);
    when(model.available()).thenReturn(true);
    when(model.extract(anyString(), anyString()))
        .thenReturn(new MeetingTaskExtractor.Result(List.of(), "fixture", 1, 2));
    var studio = new MeetingStudioService(db, tx, model, true, 2);
    var sample =
        studio.start(new MeetingStudioService.Start("sample", "weekly", null, null, "v2sample", 1));
    assertThat(sample.mode()).isEqualTo("sample");
    String insert =
        "INSERT INTO"
            + " studio_run(run_id,request_id,payload_hash,run_mode,status,created_at,state_json)"
            + " VALUES(?,?,?,'live','completed',?,'{}')";
    var now = Instant.now();
    for (int minutes : List.of(1438, 1442))
      db.update(
          insert,
          "old-" + minutes,
          "old-" + minutes,
          "a".repeat(64),
          Timestamp.from(now.minus(Duration.ofMinutes(minutes))));
    Flyway.configure().dataSource(ds).load().migrate();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM studio_live_reservation", Integer.class))
        .isEqualTo(2);
    for (int minutes : List.of(1438, 1442)) {
      long migrated =
          db.queryForObject(
              "SELECT started_ms FROM studio_live_reservation WHERE request_key=?",
              Long.class,
              "legacy:old-" + minutes);
      assertThat(migrated)
          .isCloseTo(now.minus(Duration.ofMinutes(minutes)).toEpochMilli(), within(1000L));
    }
    var live =
        new MeetingStudioService.Start("live", null, "A", "A committed to this", "first-live", 1);
    var completed = studio.start(live);
    assertThat(completed.status()).isEqualTo("completed");
    assertThat(studio.start(live)).isEqualTo(completed);
    verify(model, times(1)).extract(anyString(), anyString());
    var shared = new LiveBudget(db, model, 2);
    assertThatThrownBy(() -> tx.executeWithoutResult(t -> shared.reserve("debug:new")))
        .isInstanceOfSatisfying(GraphIssue.class, e -> assertThat(e.status()).isEqualTo(429));
    // The new legacy run has both a business receipt and a budget row; only one permit was used.
    var higherLimit = new LiveBudget(db, model, 3);
    tx.executeWithoutResult(t -> higherLimit.reserve("workflow-new"));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM studio_live_reservation", Integer.class))
        .isEqualTo(4);
    assertThatThrownBy(
            () ->
                studio.start(
                    new MeetingStudioService.Start("live", null, "B", "B", "second-live", 1)))
        .hasMessageContaining("in progress");
  }
}
