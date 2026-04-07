package mediaPipeline.model;

public enum PipelinePhase {
  IDLE,
  INGEST,
  ANALYSIS,
  VISUALS,
  AUDIO_TEXT,
  COMPLIANCE,
  PACKAGING,
  COMPLETED,
  FAILED
}
