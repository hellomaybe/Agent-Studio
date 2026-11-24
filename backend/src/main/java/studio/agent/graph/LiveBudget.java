package studio.agent.graph;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.agent.model.MeetingTaskExtractor;

/**
 * Shared admission for live model calls from the legacy meeting API, graph runs and node debugging.
 */
@Service
public class LiveBudget {
  private final JdbcTemplate db;
  private final MeetingTaskExtractor model;
  private final int dailyLimit;

  public LiveBudget(
      JdbcTemplate db,
      MeetingTaskExtractor model,
      @Value("${studio.live-daily-limit:5}") int dailyLimit) {
    this.db = db;
    this.model = model;
    this.dailyLimit = dailyLimit;
  }

  @Transactional(noRollbackFor = GraphIssue.class)
  public void reserve(String requestKey) {
    if (!model.available())
      throw new GraphIssue(503, "Live model is unavailable", null, "mode", null);
    if (requestKey == null || !requestKey.matches("[a-zA-Z0-9:_-]{1,160}"))
      throw GraphIssue.bad(null, "requestId", "Invalid request identifier");
    db.queryForObject(
        "SELECT lock_id FROM studio_live_budget_lock WHERE lock_id=1 FOR UPDATE", Integer.class);
    long now = System.currentTimeMillis();
    // These locking reads see the preceding commit even when a MySQL REPEATABLE READ caller
    // established an older snapshot before acquiring the shared admission lock.
    if (!db.queryForList(
            "SELECT request_key FROM studio_live_reservation WHERE request_key=? FOR UPDATE",
            String.class,
            requestKey)
        .isEmpty())
      throw new GraphIssue(
          409, "This request has already reserved a model call", null, "requestId", null);
    var recent =
        db.query(
            "SELECT started_ms,finished_ms FROM studio_live_reservation WHERE started_ms>? FOR"
                + " UPDATE",
            (r, i) -> r.getLong(1) > now - 120_000 && r.getObject(2) == null,
            now - 86_400_000);
    if (recent.contains(true))
      throw new GraphIssue(409, "A model call is already in progress", null, "mode", null);
    if (recent.size() >= dailyLimit)
      throw new GraphIssue(
          429, "Live model quota exhausted for this 24-hour window", null, "mode", null);
    db.update(
        "INSERT INTO studio_live_reservation(request_key,started_ms) VALUES(?,?)", requestKey, now);
  }

  @Transactional
  public void finish(String requestKey) {
    db.update(
        "UPDATE studio_live_reservation SET finished_ms=? WHERE request_key=? AND finished_ms IS"
            + " NULL",
        System.currentTimeMillis(),
        requestKey);
  }
}
