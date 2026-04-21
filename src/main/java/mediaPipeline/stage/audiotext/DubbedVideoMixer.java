package mediaPipeline.stage.audiotext;

import java.nio.file.Path;
import java.util.List;
import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.util.FfmpegUtil;

public class DubbedVideoMixer extends BaseStage {

  @Override
  public String name() {
    return "DubbedVideoMixer";
  }

  @Override
  protected StageResult run(PipelineContext ctx) {
    long t = System.currentTimeMillis();

    String dubPath = ctx.getString("dub_path");
    if (dubPath == null)
      return StageResult.fail(name(), "dub_path missing: AIDubber must run first", elapsed(t));

    @SuppressWarnings("unchecked")
    List<String> encodedAssets = (List<String>) ctx.get("encoded_assets");
    if (encodedAssets == null || encodedAssets.isEmpty())
      return StageResult.fail(
          name(), "encoded_assets missing: Transcoder must run first", elapsed(t));

    String primaryVideo =
        encodedAssets.stream()
            .filter(p -> p.contains("720p") && p.contains("h264"))
            .findFirst()
            .orElse(encodedAssets.get(0));

    String lang = ctx.getString("dub_lang");
    Path output = ctx.outputRoot().resolve("video/h264/720p_h264_dubbed_" + lang + ".mp4");

    FfmpegUtil.ProcessOutput result =
        FfmpegUtil.run(
            "ffmpeg",
            "-y",
            "-i",
            primaryVideo,
            "-i",
            dubPath,
            "-map",
            "0:v:0",
            "-map",
            "1:a:0",
            "-c:v",
            "copy",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-shortest",
            output.toAbsolutePath().toString());

    if (!result.ok())
      return StageResult.fail(name(), "Dubbed video mix failed: " + result.stderr(), elapsed(t));

    ctx.put("dubbed_video", output.toAbsolutePath().toString());
    log.info("Dubbed video → {}", output.getFileName());
    return StageResult.ok(name(), elapsed(t));
  }
}
