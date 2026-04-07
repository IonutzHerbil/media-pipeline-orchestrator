package mediaPipeline.stage;

import mediaPipeline.model.StageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseStage implements PipelineStage {

  protected final Logger log = LoggerFactory.getLogger(getClass());

  @Override
  public final StageResult execute(PipelineContext ctx) {
    long t = System.currentTimeMillis();
    log.info("starting");
    try {
      StageResult result = run(ctx);
      if (result.success()) {
        log.info("OK ({}ms)", result.durationMs());
      } else {
        log.error("FAIL: {} ({}ms)", result.message(), result.durationMs());
      }
      return result;
    } catch (Exception e) {
      long ms = System.currentTimeMillis() - t;
      log.error("EXCEPTION: {}", e.getMessage());
      return StageResult.fail(name(), e.getMessage(), ms);
    }
  }

  protected abstract StageResult run(PipelineContext ctx);

  protected long elapsed(long t) {
    return System.currentTimeMillis() - t;
  }
}
