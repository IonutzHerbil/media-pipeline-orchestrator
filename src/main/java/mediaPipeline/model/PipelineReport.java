package mediaPipeline.model;

import java.util.List;

public record PipelineReport(
    String movieId, PipelinePhase finalPhase, List<StageResult> results, long totalMs) {

  public boolean succeeded() {
    return finalPhase == PipelinePhase.COMPLETED;
  }

  public void print() {
    System.out.printf("%n=== Pipeline Report: %s ===%n", movieId);
    System.out.printf("Status : %s%n", finalPhase);
    System.out.printf("Total  : %.2fs%n%n", totalMs / 1000.0);
    for (StageResult r : results) {
      System.out.printf(
          "  [%s] %-30s  %dms  %s%n",
          r.success() ? "OK" : "FAIL", r.stageName(), r.durationMs(), r.message());
    }
  }
}
