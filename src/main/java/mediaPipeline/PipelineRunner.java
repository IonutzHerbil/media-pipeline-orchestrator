package mediaPipeline;

import mediaPipeline.model.StageResult;
import mediaPipeline.model.VideoFile;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.stage.ingest.FormatValidator;
import mediaPipeline.stage.ingest.IntegrityCheck;

import java.nio.file.Path;

public class PipelineRunner {
  public static void main(String[] args) {
    String videoPath = args.length > 0 ? args[0] : "sample.mp4";
    String movieId = args.length > 1 ? args[1] : "movie_101";

    System.out.println("=== Pipeline Runner ===");

    var video = new VideoFile(movieId, Path.of(videoPath));
    var ctx = new PipelineContext(video, Path.of("output/" + movieId));

    StageResult r1 = new IntegrityCheck().execute(ctx);
    StageResult r2 = new FormatValidator().execute(ctx);

    System.out.println("\n--- Results ---");
    System.out.println(r1);
    System.out.println(r2);
    System.out.println(
        r1.success() && r2.success() ? "=== INGEST OK ===" : "=== INGEST FAILED ===");
  }
}
