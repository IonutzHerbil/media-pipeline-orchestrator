package mediaPipeline.stage.audiotext;

import java.nio.file.Path;
import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.util.FfmpegUtil;
import mediaPipeline.util.PipelineConfig;
import mediaPipeline.util.PythonRunner;

public class SpeechToText extends BaseStage {

  @Override
  public String name() {
    return "SpeechToText";
  }

  @Override
  protected StageResult run(PipelineContext ctx) {
    long t = System.currentTimeMillis();
    String src = ctx.video().sourcePath().toAbsolutePath().toString();
    Path out = ctx.outputRoot().resolve("text/source_transcript.txt");

    FfmpegUtil.ProcessOutput result =
        PythonRunner.run(
            PipelineConfig.scriptsDir() + "/transcribe.py", src, out.toAbsolutePath().toString());

    if (!result.ok()) return StageResult.fail(name(), result.stderr(), elapsed(t));

    if (!out.toFile().exists() || out.toFile().length() == 0)
      return StageResult.fail(name(), "Transcript empty or missing", elapsed(t));

    ctx.put("transcript_path", out.toAbsolutePath().toString());
    log.info("Transcript → {}", out);
    return StageResult.ok(name(), elapsed(t));
  }
}
