package studio.agent.graph;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class GraphErrors {
  @ExceptionHandler(GraphIssue.class)
  ProblemDetail issue(GraphIssue ex) {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(ex.status()), ex.getMessage());
    if (ex.nodeId() != null) problem.setProperty("nodeId", ex.nodeId());
    if (ex.field() != null) problem.setProperty("field", ex.field());
    if (ex.port() != null) problem.setProperty("port", ex.port());
    ex.diagnostics.forEach(problem::setProperty);
    return problem;
  }
}
