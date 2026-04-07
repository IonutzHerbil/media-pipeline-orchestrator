package mediaPipeline.stage;

import mediaPipeline.model.StageResult;

public interface PipelineStage {
  StageResult execute(PipelineContext context);

  String name();
}
