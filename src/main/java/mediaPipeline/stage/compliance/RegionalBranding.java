package mediaPipeline.stage.compliance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.util.FfmpegUtil;

public class RegionalBranding extends BaseStage {

  private static final String LOGO_RESOURCE = "/branding/studio_logo.png";
  private static final int LOGO_X = 10;
  private static final int LOGO_Y = 10;
  private static final int LOGO_WIDTH = 200;
  private static final double LOGO_OPACITY = 0.4;

  @Override
  public String name() {
    return "RegionalBranding";
  }

  @Override
  protected StageResult run(PipelineContext ctx) {
    long t = System.currentTimeMillis();

    Path logoPath = extractLogo(ctx);
    if (logoPath == null)
      return StageResult.fail(name(), "Could not load studio_logo.png from resources", elapsed(t));

    @SuppressWarnings("unchecked")
    List<String> encodedAssets = (List<String>) ctx.get("encoded_assets");
    if (encodedAssets == null || encodedAssets.isEmpty()) {
      log.warn("No encoded assets found: skipping branding.");
      return StageResult.ok(name(), elapsed(t));
    }

    String primary =
        encodedAssets.stream()
            .filter(p -> p.contains("720p") && p.contains("h264"))
            .findFirst()
            .orElse(encodedAssets.get(0));

    Path input = Path.of(primary);
    Path output =
        input.getParent().resolve(input.getFileName().toString().replace(".", "_branded."));

    FfmpegUtil.ProcessOutput result =
        FfmpegUtil.run(
            "ffmpeg",
            "-y",
            "-i",
            primary,
            "-i",
            logoPath.toAbsolutePath().toString(),
            "-filter_complex",
            String.format(
                "[1:v]scale=%d:-1,format=rgba,colorchannelmixer=aa=%.1f[logo];"
                    + "[0:v][logo]overlay=%d:%d",
                LOGO_WIDTH, LOGO_OPACITY, LOGO_X, LOGO_Y),
            "-c:a",
            "copy",
            "-c:v",
            "libx264",
            "-preset",
            "fast",
            "-crf",
            "23",
            output.toAbsolutePath().toString());

    if (!result.ok())
      return StageResult.fail(name(), "Logo overlay failed: " + result.stderr(), elapsed(t));

    ctx.put("branded_asset", output.toAbsolutePath().toString());
    log.info("Logo applied to {} → {}", input.getFileName(), output.getFileName());
    return StageResult.ok(name(), elapsed(t));
  }

  private Path extractLogo(PipelineContext ctx) {
    Path dest = ctx.outputRoot().resolve("metadata/studio_logo.png");
    try (InputStream is = getClass().getResourceAsStream(LOGO_RESOURCE)) {
      if (is == null) return null;
      Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
      return dest;
    } catch (IOException e) {
      log.error("Failed to extract logo: {}", e.getMessage());
      return null;
    }
  }
}
