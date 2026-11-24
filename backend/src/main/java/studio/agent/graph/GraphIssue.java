package studio.agent.graph;

/** Carries a user-facing validation problem without exposing storage/provider exceptions. */
public final class GraphIssue extends RuntimeException {
  final java.util.Map<String, Object> diagnostics = new java.util.LinkedHashMap<>();
  private final int status;
  private final String nodeId;
  private final String field;
  private final String port;

  GraphIssue(int status, String message, String nodeId, String field, String port) {
    super(message);
    this.status = status;
    this.nodeId = nodeId;
    this.field = field;
    this.port = port;
  }

  public int status() {
    return status;
  }

  public String nodeId() {
    return nodeId;
  }

  public String field() {
    return field;
  }

  public String port() {
    return port;
  }

  static GraphIssue bad(String id, String field, String message) {
    return new GraphIssue(400, message, id, field, null);
  }
}
