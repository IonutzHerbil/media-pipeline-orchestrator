package mediaPipeline.stage.audiotext;

import java.nio.file.Path;
import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.util.FfmpegUtil;
import mediaPipeline.util.PipelineConfig;
import mediaPipeline.util.PythonRunner;

public class Translator extends BaseStage {

  @Override
  public String name() {
    return "Translator";
  }

  @Override
  protected StageResult run(PipelineContext ctx) {
    long t = System.currentTimeMillis();

    String transcriptPath = ctx.getString("transcript_path");
    if (transcriptPath == null)
      return StageResult.fail(
          name(), "transcript_path missing: SpeechToText must run first", elapsed(t));

    Path textDir = ctx.outputRoot().resolve("text");
    String script = PipelineConfig.scriptsDir() + "/translate.py";

    FfmpegUtil.ProcessOutput result =
        PythonRunner.run(script, transcriptPath, textDir.toAbsolutePath().toString());

    if (!result.ok())
      return StageResult.fail(name(), "translate.py failed: " + result.stderr(), elapsed(t));

    ctx.put("text_dir", textDir.toAbsolutePath().toString());
    log.info("Translations written to {}", textDir);
    return StageResult.ok(name(), elapsed(t));
  }
}
