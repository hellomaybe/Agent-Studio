package studio.agent.workflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(
    name = "studio.workflow.scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class WorkflowWorker {
  private final WorkflowRunService runs;

  public WorkflowWorker(WorkflowRunService runs) {
    this.runs = runs;
  }

  @Scheduled(fixedDelayString = "${studio.workflow.tick-ms:250}")
  void advance() {
    runs.tick();
  }
}
