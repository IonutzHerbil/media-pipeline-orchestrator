package mediaPipeline.stage.audiotext;

import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.util.FfmpegUtil;
import mediaPipeline.util.PipelineConfig;
import mediaPipeline.util.PythonRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class AIDubber extends BaseStage {

    @Override
    public String name() { return "AIDubber"; }

    @Override
    protected StageResult run(PipelineContext ctx) {
        long t = System.currentTimeMillis();

        String textDir = ctx.getString("text_dir");
        if (textDir == null)
            return StageResult.fail(name(), "text_dir missing — Translator must run first", elapsed(t));

        Path translationFile = findTranslationFile(Path.of(textDir));
        if (translationFile == null)
            return StageResult.fail(name(), "No translation file found in " + textDir, elapsed(t));

        String langCode  = detectLangCode(translationFile);
        Path   outputAac = ctx.outputRoot().resolve("audio")
                .resolve(langCode + "_dub_synthetic.aac");
        String script    = PipelineConfig.scriptsDir() + "/dub.py";

        log.info("Dubbing {} → {}", translationFile.getFileName(), outputAac.getFileName());

        FfmpegUtil.ProcessOutput result = PythonRunner.run(
                script,
                translationFile.toAbsolutePath().toString(),
                langCode,
                outputAac.toAbsolutePath().toString()
        );

        if (!result.ok())
            return StageResult.fail(name(), "dub.py failed: " + result.stderr(), elapsed(t));

        if (!Files.exists(outputAac))
            return StageResult.fail(name(), "AAC output missing after dub", elapsed(t));

        ctx.put("dub_path", outputAac.toAbsolutePath().toString());
        ctx.put("dub_lang", langCode);
        log.info("Dub → {}", outputAac);
        return StageResult.ok(name(), elapsed(t));
    }

    private Path findTranslationFile(Path textDir) {
        try {
            Optional<Path> file = Files.list(textDir)
                    .filter(p -> p.getFileName().toString().endsWith("_translation.txt"))
                    .findFirst();
            return file.orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private String detectLangCode(Path translationFile) {
        String name = translationFile.getFileName().toString();
        return name.replace("_translation.txt", "");
    }
}