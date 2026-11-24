package studio.agent.meeting;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import studio.agent.meeting.MeetingGraph.Config;
import studio.agent.meeting.MeetingGraph.Preview;
import studio.agent.meeting.MeetingGraph.Problem;
import studio.agent.meeting.MeetingStudioService.Confirm;
import studio.agent.meeting.MeetingStudioService.Draft;
import studio.agent.meeting.MeetingStudioService.Run;
import studio.agent.meeting.MeetingStudioService.Save;
import studio.agent.meeting.MeetingStudioService.Start;

/** A local, single-operator workspace served on loopback by default. */
@RestController
@RequestMapping("/api/studio/meeting-notes")
public class MeetingStudioController {
  public record PreviewRequest(Config config, String sampleKey) {}

  public record Response<T>(int code, String msg, T data) {
    static <T> Response<T> ok(T data) {
      return new Response<>(200, "OK", data);
    }
  }

  public record Error(int code, String msg, String message) {}

  private final MeetingStudioService studio;

  public MeetingStudioController(MeetingStudioService studio) {
    this.studio = studio;
  }

  @GetMapping("/draft")
  public Response<Draft> draft() {
    return Response.ok(studio.draft());
  }

  @PutMapping("/draft")
  public Response<Draft> save(@RequestBody Save save) {
    return Response.ok(studio.save(save));
  }

  @PostMapping("/preview")
  public Response<Preview> preview(@RequestBody PreviewRequest request) {
    return Response.ok(MeetingGraph.preview(request.config(), request.sampleKey()));
  }

  @PostMapping("/runs")
  public Response<Run> start(@RequestBody Start request) {
    return Response.ok(studio.start(request));
  }

  @GetMapping("/runs")
  public Response<List<Run>> list() {
    return Response.ok(studio.list());
  }

  @GetMapping("/runs/{id}")
  public Response<Run> get(@PathVariable String id) {
    return Response.ok(studio.get(id));
  }

  @PostMapping("/runs/{id}/confirm")
  public Response<Run> confirm(@PathVariable String id, @RequestBody Confirm request) {
    return Response.ok(studio.confirm(id, request));
  }

  @ExceptionHandler(Problem.class)
  public ResponseEntity<Error> problem(Problem problem) {
    return error(problem.status, problem.getMessage());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Error> malformedRequest() {
    return error(400, "请求 JSON 格式或字段类型不正确。");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Error> internalFailure() {
    return error(500, "工作台暂时无法完成此请求。");
  }

  private static ResponseEntity<Error> error(int status, String message) {
    return ResponseEntity.status(status).body(new Error(status, message, message));
  }
}
