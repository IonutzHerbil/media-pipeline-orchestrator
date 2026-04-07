package mediaPipeline.stage;

import mediaPipeline.model.VideoFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PipelineContext {

  private final VideoFile video;
  private final Path outputRoot;
  private final Map<String, Object> metadata = new ConcurrentHashMap<>();

  public PipelineContext(VideoFile video, Path outputRoot) {
    this.video = video;
    this.outputRoot = outputRoot;
    try {
      createOutputDirs();
    } catch (IOException e) {
      throw new RuntimeException("Failed to create output dirs: " + e.getMessage(), e);
    }
  }

  private void createOutputDirs() throws IOException {
    Files.createDirectories(outputRoot.resolve("video/h264"));
    Files.createDirectories(outputRoot.resolve("video/vp9"));
    Files.createDirectories(outputRoot.resolve("video/hevc"));
    Files.createDirectories(outputRoot.resolve("images/thumbnails"));
    Files.createDirectories(outputRoot.resolve("text"));
    Files.createDirectories(outputRoot.resolve("audio"));
    Files.createDirectories(outputRoot.resolve("metadata"));
  }

  public VideoFile video() {
    return video;
  }

  public Path outputRoot() {
    return outputRoot;
  }

  public void put(String key, Object v) {
    metadata.put(key, v);
  }

  public Object get(String key) {
    return metadata.get(key);
  }

  public String getString(String key) {
    return (String) metadata.get(key);
  }
}
