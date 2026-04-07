package mediaPipeline.model;

public record StageResult(String stageName, boolean success, String message, long durationMs) {

  public static StageResult ok(String name, long ms) {
    return new StageResult(name, true, "OK", ms);
  }

  public static StageResult fail(String name, String msg, long ms) {
    return new StageResult(name, false, msg, ms);
  }
}
