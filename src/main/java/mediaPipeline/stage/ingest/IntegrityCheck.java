package mediaPipeline.stage.ingest;

import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class IntegrityCheck extends BaseStage {

  private static final int BUFFER_SIZE = 1024 * 1024;

  private static final Map<String, int[]> SIGNATURES =
      Map.of(
          "MP4", new int[] {4, 'f', 't', 'y', 'p'},
          "MKV", new int[] {0, 0x1A, 0x45, 0xDF, 0xA3},
          "WEBM", new int[] {0, 0x1A, 0x45, 0xDF, 0xA3}
      );

  @Override
  public String name() {
    return "IntegrityCheck";
  }

  @Override
  protected StageResult run(PipelineContext ctx) {
    long t = System.currentTimeMillis();
    Path path = ctx.video().sourcePath().toAbsolutePath();

    if (!path.toFile().exists()) {
      return StageResult.fail(name(), "File not found: " + path, elapsed(t));
    }
    if (!path.toFile().canRead()) {
      return StageResult.fail(name(), "File is not readable (check permissions): " + path, elapsed(t));
    }

    long fileSize;
    try {
      fileSize = Files.size(path);
    } catch (IOException e) {
      return StageResult.fail(name(), "Could not read file size: " + e.getMessage(), elapsed(t));
    }
    if (fileSize == 0) {
      return StageResult.fail(name(), "File is empty: " + path, elapsed(t));
    }
    double sizeMB = fileSize / (1024.0 * 1024.0);

    log.info("File size: {} bytes ({} MB)", fileSize, String.format("%.2f", sizeMB));
    ctx.put("file_size", fileSize);

    return StageResult.ok(name(), elapsed(t));
  }

}
