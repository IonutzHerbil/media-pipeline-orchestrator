package mediaPipeline.stage.ingest;

import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.util.FfmpegUtil;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FormatValidator extends BaseStage {

  private static final List<String> ACCEPTED_VIDEO_CODECS =
      List.of("h264", "hevc", "prores", "dnxhd", "mpeg4", "vp9");
  private static final int MIN_WIDTH =
      Integer.parseInt(System.getProperty("pipeline.minWidth", "1280"));
  private static final int MIN_HEIGHT =
      Integer.parseInt(System.getProperty("pipeline.minHeight", "720"));
  private static final double MIN_DURATION_SECONDS = 10.0;

  @Override
  public String name() {
    return "FormatValidator";
  }

  @Override
  protected StageResult run(PipelineContext ctx) {
    long t = System.currentTimeMillis();
    String path = ctx.video().sourcePath().toAbsolutePath().toString();

    FfmpegUtil.ProcessOutput probe =
        FfmpegUtil.run(
            "ffprobe",
            "-v",
            "error",
            "-show_streams",
            "-show_format",
            "-of",
            "flat=sep_char=.",
            path);

    if (!probe.ok()) {
      return StageResult.fail(
          name(),
          "ffprobe exited with code " + probe.exitCode() + ": " + probe.stderr(),
          elapsed(t));
    }

    String flat = probe.stdout();
    ctx.put("ffprobe_info", flat);
    log.debug("ffprobe output:\n{}", flat);

    StreamInfo video = findStream(flat, "video");
    StreamInfo audio = findStream(flat, "audio");

    if (video == null) {
      return StageResult.fail(name(), "No video stream found in source file", elapsed(t));
    }
    log.info("Video stream -> codec={}, resolution={}x{}", video.codec, video.width, video.height);

    if (!ACCEPTED_VIDEO_CODECS.contains(video.codec.toLowerCase())) {
      return StageResult.fail(
          name(),
          "Unsupported video codec '" + video.codec + "'. Accepted: " + ACCEPTED_VIDEO_CODECS,
          elapsed(t));
    }
    if (video.width < MIN_WIDTH || video.height < MIN_HEIGHT) {
      return StageResult.fail(
          name(),
          String.format(
              "Resolution %dx%d is below the minimum %dx%d",
              video.width, video.height, MIN_WIDTH, MIN_HEIGHT),
          elapsed(t));
    }

    if (audio == null) {
      return StageResult.fail(
          name(), "No audio stream found -> source must have at least one audio track", elapsed(t));
    }
    log.info("Audio stream -> codec={}", audio.codec);

    double duration = parseDuration(flat);
    if (duration < MIN_DURATION_SECONDS) {
      return StageResult.fail(
          name(),
          String.format(
              "Duration %.2fs is suspiciously short (minimum %.0fs). File may be corrupt.",
              duration, MIN_DURATION_SECONDS),
          elapsed(t));
    }
    log.info("Duration: {}s", String.format("%.2f", duration));

    ctx.put("duration", duration);
    ctx.put("video_codec", video.codec);
    ctx.put("width", video.width);
    ctx.put("height", video.height);
    ctx.put("audio_codec", audio.codec);

    log.info(
        "Format validation passed -> {}x{} {} @ {}s",
        video.width,
        video.height,
        video.codec,
        String.format("%.2f", duration));

    return StageResult.ok(name(), elapsed(t));
  }

  private StreamInfo findStream(String flat, String type) {
    Pattern idxPattern =
        Pattern.compile("streams\\.stream\\.(\\d+)\\.codec_type=\"?" + type + "\"?");
    Matcher m = idxPattern.matcher(flat);
    if (!m.find()) return null;

    int idx = Integer.parseInt(m.group(1));
    String prefix = "streams.stream." + idx + ".";

    String codec = extractValue(flat, prefix + "codec_name");
    int width = parseInt(extractValue(flat, prefix + "width"), 0);
    int height = parseInt(extractValue(flat, prefix + "height"), 0);

    if (codec == null) return null;
    return new StreamInfo(codec, width, height);
  }

  private double parseDuration(String flat) {
    String val = extractValue(flat, "format.duration");
    if (val == null) val = extractValue(flat, "streams.stream.0.duration");
    if (val == null) return 0.0;
    try {
      return Double.parseDouble(val);
    } catch (NumberFormatException e) {
      return 0.0;
    }
  }

  private String extractValue(String flat, String key) {
    Pattern p = Pattern.compile(Pattern.quote(key) + "=\"?([^\"\\n]+)\"?");
    Matcher m = p.matcher(flat);
    return m.find() ? m.group(1).trim() : null;
  }

  private int parseInt(String s, int fallback) {
    if (s == null) return fallback;
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private record StreamInfo(String codec, int width, int height) {}
}
