package mediaPipeline;

import mediaPipeline.model.PipelinePhase;
import mediaPipeline.model.PipelineReport;
import mediaPipeline.model.StageResult;
import mediaPipeline.model.VideoFile;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.stage.PipelineStage;
import mediaPipeline.stage.analysis.CreditRoller;
import mediaPipeline.stage.analysis.IntroOutroDetector;
import mediaPipeline.stage.analysis.SceneIndexer;
import mediaPipeline.stage.ingest.FormatValidator;
import mediaPipeline.stage.ingest.IntegrityCheck;
import mediaPipeline.stage.visuals.SceneComplexity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class WorkflowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOrchestrator.class);

    private PipelinePhase           currentPhase = PipelinePhase.IDLE;
    private final List<StageResult> accumulator  = new ArrayList<>();
    private final PipelineContext   ctx;
    private final long              startMs      = System.currentTimeMillis();

    public WorkflowOrchestrator(VideoFile video, String outputRoot) {
        this.ctx = new PipelineContext(video, Path.of(outputRoot));
    }

    public PipelineReport run() {
        transition(PipelinePhase.INGEST);
        if (!runIngest())      return finish();

        transition(PipelinePhase.ANALYSIS);
        if (!runAnalysis())    return finish();

        transition(PipelinePhase.VISUALS);
        if (!runVisuals())     return finish();

        transition(PipelinePhase.AUDIO_TEXT);
        if (!runAudioText())   return finish();

        transition(PipelinePhase.COMPLIANCE);
        if (!runCompliance())  return finish();

        transition(PipelinePhase.PACKAGING);
        if (!runPackaging())   return finish();

        transition(PipelinePhase.COMPLETED);
        return finish();
    }

    private boolean runIngest() {
        return runSequential(List.of(
                new IntegrityCheck(),
                new FormatValidator()
        ));
    }

    private boolean runAnalysis() {
        return runSequential(List.of(
                new SceneIndexer(),
                new IntroOutroDetector(),
                new CreditRoller()
        ));
    }

    private boolean runVisuals() {
        return runSequential(List.of(
                new SceneComplexity()
        ));
    }

    private boolean runAudioText() {
        return true;
    }

    private boolean runCompliance() {
        return true;
    }

    private boolean runPackaging() {
        return true;
    }

    boolean runSequential(List<PipelineStage> stages) {
        for (PipelineStage stage : stages) {
            StageResult result = stage.execute(ctx);
            accumulator.add(result);
            if (!result.success()) {
                log.error("[{}] {} failed: {} — aborting phase.",
                        currentPhase, stage.name(), result.message());
                transition(PipelinePhase.FAILED);
                return false;
            }
        }
        return true;
    }

    private void transition(PipelinePhase next) {
        log.info("Phase: {} → {}", currentPhase, next);
        currentPhase = next;
    }

    private PipelineReport finish() {
        long totalMs = System.currentTimeMillis() - startMs;
        return new PipelineReport(
                ctx.video().movieId(),
                currentPhase,
                List.copyOf(accumulator),
                totalMs);
    }

    PipelineContext context() { return ctx; }
}