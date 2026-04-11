package mediaPipeline;

import mediaPipeline.model.PipelineReport;
import mediaPipeline.model.VideoFile;

import java.nio.file.Path;

public class PipelineRunner {

  public static void main(String[] args) {
    String videoPath = args.length > 0 ? args[0] : "sample.mp4";
    String movieId   = args.length > 1 ? args[1] : "movie_101";

    var video        = new VideoFile(movieId, Path.of(videoPath));
    var orchestrator = new WorkflowOrchestrator(video, "output/" + movieId);

    PipelineReport report = orchestrator.run();
    report.print();

    System.exit(report.succeeded() ? 0 : 1);
  }
}