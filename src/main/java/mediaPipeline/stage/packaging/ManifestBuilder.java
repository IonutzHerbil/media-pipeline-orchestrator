package mediaPipeline.stage.packaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;

public class ManifestBuilder extends BaseStage {

  @Override
  public String name() {
    return "ManifestBuilder";
  }

  @Override
  protected StageResult run(PipelineContext ctx) {
    long t = System.currentTimeMillis();

    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("movie_id", ctx.video().movieId());
    manifest.put("pipeline_version", "1.0");
    manifest.put("created_at", Instant.now().toString());

    manifest.put(
            "source",
            Map.of(
                    "checksum_sha256", orEmpty(ctx, "source_checksum"),
                    "duration_s", orZero(ctx, "duration"),
                    "resolution", ctx.get("width") + "x" + ctx.get("height"),
                    "codec", orEmpty(ctx, "video_codec"),
                    "container", orEmpty(ctx, "container_format")));

    Map<String, Object> analysis = new LinkedHashMap<>();
    analysis.put("intro_end_ts", orZero(ctx, "intro_end_ts"));
    analysis.put("credits_start_ts", orZero(ctx, "credits_start_ts"));
    analysis.put("scene_count", orZero(ctx, "scene_count"));
    analysis.put("dominant_crf", orZero(ctx, "suggested_crf"));
    analysis.put("avg_crf", orZero(ctx, "avg_crf"));
    if (ctx.get("outro_start_ts") != null)
      analysis.put("outro_start_ts", orZero(ctx, "outro_start_ts"));
    manifest.put("analysis", analysis);

    @SuppressWarnings("unchecked")
    List<String> encodedAssets = (List<String>) ctx.get("encoded_assets");
    List<Map<String, Object>> videoAssets = new ArrayList<>();
    if (encodedAssets != null) {
      for (String path : encodedAssets) {
        Path p = Path.of(path);
        videoAssets.add(
                Map.of(
                        "path", rel(ctx, p),
                        "size_bytes", fileSize(p),
                        "checksum", checksum(p)));
      }
    }

    manifest.put(
            "assets",
            Map.of(
                    "video", videoAssets,
                    "images",
                    Map.of(
                            "sprite_map", rel(ctx, ctx.outputRoot().resolve("images/sprite_map.jpg")),
                            "thumbnail_count", orZero(ctx, "thumbnail_count"),
                            "thumbnail_interval", orZero(ctx, "thumb_interval_s")),
                    "text",
                    Map.of(
                            "source_transcript",
                            "text/source_transcript.txt",
                            "translations",
                            listTextFiles(ctx.outputRoot().resolve("text"))),
                    "audio",
                    Map.of(
                            "dub_path", relOrEmpty(ctx, "dub_path"),
                            "dub_lang", orEmpty(ctx, "dub_lang"))));

    manifest.put(
            "compliance",
            Map.of(
                    "safety_flags", ctx.get("safety_flags") != null ? ctx.get("safety_flags") : List.of(),
                    "censored_video", relOrEmpty(ctx, "censored_video"),
                    "branded_asset", relOrEmpty(ctx, "branded_asset")));

    @SuppressWarnings("unchecked")
    List<Map<String, String>> encryptedAssets =
            (List<Map<String, String>>) ctx.get("encrypted_assets");
    List<Map<String, Object>> encList = new ArrayList<>();
    if (encryptedAssets != null) {
      for (Map<String, String> entry : encryptedAssets) {
        String path = entry.get("path");
        encList.add(Map.of(
                "path", rel(ctx, Path.of(path)),
                "iv",   entry.getOrDefault("iv", "")));
      }
    }

    manifest.put("drm", Map.of("method", orEmpty(ctx, "drm_method"), "encrypted_assets", encList));

    Path output = ctx.outputRoot().resolve("metadata/manifest.json");
    try {
      new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), manifest);
    } catch (IOException e) {
      return StageResult.fail(
              name(), "Could not write manifest.json: " + e.getMessage(), elapsed(t));
    }

    log.info("Manifest → {}", output);
    return StageResult.ok(name(), elapsed(t));
  }

  private String rel(PipelineContext ctx, Path path) {
    try {
      return ctx.outputRoot().relativize(path).toString().replace("\\", "/");
    } catch (Exception e) {
      return path.toString().replace("\\", "/");
    }
  }

  private String relOrEmpty(PipelineContext ctx, String key) {
    Object v = ctx.get(key);
    if (v == null) return "";
    return rel(ctx, Path.of(v.toString()));
  }

  private long fileSize(Path p) {
    try {
      return Files.exists(p) ? Files.size(p) : 0;
    } catch (IOException e) {
      return 0;
    }
  }

  private String checksum(Path p) {
    if (!Files.exists(p)) return "";
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[64 * 1024];
      try (InputStream is = Files.newInputStream(p)) {
        int read;
        while ((read = is.read(buf)) != -1) digest.update(buf, 0, read);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      return "";
    }
  }

  private List<String> listTextFiles(Path dir) {
    try {
      return Files.list(dir)
              .filter(p -> p.toString().endsWith(".txt"))
              .map(p -> p.getFileName().toString())
              .toList();
    } catch (IOException e) {
      return List.of();
    }
  }

  private String orEmpty(PipelineContext ctx, String key) {
    Object v = ctx.get(key);
    return v != null ? v.toString() : "";
  }

  private double orZero(PipelineContext ctx, String key) {
    Object v = ctx.get(key);
    if (v instanceof Number n) return n.doubleValue();
    return 0;
  }
}