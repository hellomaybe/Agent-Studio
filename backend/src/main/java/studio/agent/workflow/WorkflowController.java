package studio.agent.workflow;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import studio.agent.graph.GraphIssue;
import studio.agent.workflow.WorkflowRunService.*;

@RestController
@RequestMapping("/api/apps/{appId}/runs")
public class WorkflowController {
  public record Success<T>(int code, String msg, T data) {
    static <T> Success<T> ok(T data) {
      return new Success<>(200, "OK", data);
    }
  }

  public record Failure(int code, String msg, String message) {}

  private final WorkflowRunService service;

  public WorkflowController(WorkflowRunService service) {
    this.service = service;
  }

  @ModelAttribute
  void privateResponse(jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
  }

  @PostMapping
  public Success<View> start(@PathVariable String appId, @RequestBody Start start) {
    return Success.ok(service.start(appId, start));
  }

  @GetMapping
  public Success<List<HistoryEntry>> list(@PathVariable String appId) {
    return Success.ok(service.list(appId));
  }

  @GetMapping("/{id}")
  public Success<View> detail(@PathVariable String appId, @PathVariable String id) {
    return Success.ok(service.get(appId, id));
  }

  @GetMapping("/{id}/events")
  public Success<EventPage> events(
      @PathVariable String appId,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "100") int limit) {
    return Success.ok(service.events(appId, id, after, limit));
  }

  @PostMapping("/{id}/debug-input")
  public ResponseEntity<Success<DebugInput>> debugInput(
      @PathVariable String appId, @PathVariable String id, @RequestBody DebugRequest request) {
    return ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(Success.ok(service.debugInput(appId, id, request)));
  }

  @PostMapping("/{id}/reconcile")
  public Success<View> reconcile(
      @PathVariable String appId, @PathVariable String id, @RequestBody Review input) {
    return Success.ok(service.review(appId, id, input));
  }

  @PostMapping("/{id}/reinput")
  public Success<View> reinput(
      @PathVariable String appId, @PathVariable String id, @RequestBody Reinput input) {
    return Success.ok(service.reinput(appId, id, input));
  }

  @PostMapping("/{id}/{action:resume|pause|continue|retry}")
  public Success<View> control(
      @PathVariable String appId,
      @PathVariable String id,
      @PathVariable String action,
      @RequestBody Signal signal) {
    return Success.ok(service.signal(appId, id, action, signal));
  }

  @ExceptionHandler(Fault.class)
  ResponseEntity<Failure> rejected(Fault e) {
    return failure(e.status, e.getMessage());
  }

  @ExceptionHandler(GraphIssue.class)
  ResponseEntity<Failure> graph(GraphIssue e) {
    return failure(e.status(), e.getMessage());
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<Failure> malformed() {
    return failure(400, "请求 JSON 格式或字段类型不正确。");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Failure> internal() {
    return failure(500, "运行状态暂时无法读取，请按原请求编号核对。");
  }

  private static ResponseEntity<Failure> failure(int code, String message) {
    return ResponseEntity.status(code).body(new Failure(code, message, message));
  }
}
