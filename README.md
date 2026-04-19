# Media Pipeline Orchestrator

The core Java application responsible for mediating the media data transformation process. It acts as the central control plane for the [Media Data Pipeline](https://github.com/IonutzHerbil/media-pipeline).

## Architecture and Design Patterns

The orchestrator is built on a highly modular, extensible architecture designed to handle complex media processing workflows:

* **State Machine Execution:** The `WorkflowOrchestrator` manages execution through strictly defined enumerations (`PipelinePhase`: INGEST, ANALYSIS, VISUALS, AUDIO_TEXT, COMPLIANCE, PACKAGING). 
* **Concurrency Model:** The system utilizes `java.util.concurrent.ExecutorService` and `CompletableFuture` to execute computationally expensive, independent stages in parallel. For instance, `Transcoder` and `SpriteGenerator` run concurrently, and within `Transcoder`, different resolution/codec permutations are dispatched to a thread pool scaled to the host machine's available processors.
* **Shared Context (`PipelineContext`):** A thread-safe, centralized context object is passed sequentially through the pipeline. Stages publish extracted metadata (e.g., timestamps, detected resolutions, generated file paths) to a `ConcurrentHashMap` within the context, allowing downstream stages to consume them without tight coupling.
* **Stage Abstraction:** All pipeline tasks implement the `PipelineStage` interface and extend `BaseStage`, ensuring uniform error handling, duration tracking, and logging output.

## Detailed Stage Breakdown

### 1. Ingest
* **IntegrityCheck:** Reads file headers to detect magic bytes (MP4, MKV, WEBM) and computes a SHA-256 checksum of the master file.
* **FormatValidator:** Wraps `ffprobe` to validate that the source container possesses at least one valid video and audio stream, meets the minimum resolution threshold (1280x720), and utilizes a supported codec.

### 2. Analysis
* **SceneIndexer:** Analyzes frame-by-frame packet sizes via `ffprobe` to index scenes based on visual complexity (e.g., action sequences vs. dialogue). This directly informs the Constant Rate Factor (CRF) used in the Visuals phase.
* **Intro/Outro & Credit Detection:** Utilizes FFmpeg's `silencedetect` and `ebur128` (loudness drop) filters to programmatically determine the bounds of intro themes, outro sequences, and credit rolls.

### 3. Visuals
* **Transcoder:** Generates a matrix of streamable assets. It supports H.264, HEVC, and VP9. Notably, VP9 encoding is implemented using an automated two-pass approach for optimal bitrate distribution.
* **SpriteGenerator:** Extracts periodic thumbnail keyframes based on total duration and tiles them into a cohesive sprite map grid (160px width, 10 columns) for client-side scrub-bar rendering.

### 4. Audio/Text
* Orchestrates external Python workers for transcription, translation, and synthetic dubbing.
* **DubbedVideoMixer:** Multiplexes the generated synthetic AAC audio track back into a designated H.264 video container using `-c:v copy`.

### 5. Compliance
* **SafetyScanner:** Parses the timed transcript against categorical blacklists (profanity, hate speech, violence) and utilizes FFmpeg's `signalstats` to detect high-frequency luminance changes indicative of epilepsy risks (Harding test).
* **ContentCensor:** Conditionally applies `volume=0` filters for profanity and `boxblur` filters for visual hazards. It intelligently skips re-encoding (`-c:v copy`) if no visual filters are required.
* **RegionalBranding:** Extracts a studio logo from resources and applies a transparent overlay (`colorchannelmixer`) to the primary asset.

### 6. Packaging
* **DRMWrapper:** Applies AES-256-CTR encryption natively via `javax.crypto.Cipher` to all encoded video assets, generating randomized Initialization Vectors (IV) per file.
* **ManifestBuilder:** Compiles all accumulated metadata, checksums, localized asset paths, and DRM keys into the final `manifest.json`.

## Prerequisites and Execution

* **Java 17+:** Required for compilation and execution.
* **FFmpeg & FFprobe:** Must be globally accessible in the system PATH.
* **Workers Path:** The `media-pipeline-workers` repository must be accessible. The default lookup directory is `../media-pipeline-workers/scripts`, which can be overridden in `pipeline.properties`.
