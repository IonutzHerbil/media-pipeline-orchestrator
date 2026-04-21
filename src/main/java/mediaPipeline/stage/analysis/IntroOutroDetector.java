package mediaPipeline.stage.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.util.FfmpegUtil;

public class IntroOutroDetector extends BaseStage {

  private static final double INTRO_WINDOW_S = 300.0;
  private static final double OUTRO_WINDOW_S = 300.0;
  private static final double MIN_INTRO_LENGTH_S = 10.0;
  private static final double SCENE_THRESHOLD = 0.3;
  private static final String NOISE_FLOOR = "-30dB";
  private static final double OUTRO_MIN_SILENCE_S = 0.5;

  @Override
  public String name() {
    return "IntroOutroDetector";
  }

  @Override
  protected StageResult run(PipelineContext ctx) {
    long t = System.currentTimeMillis();
    double duration = getDouble(ctx, "duration");

    if (duration <= 0)
      return StageResult.fail(
          name(), "duration missing: FormatValidator must run first", elapsed(t));

    String source = ctx.video().sourcePath().toAbsolutePath().toString();
    double introWin = Math.min(INTRO_WINDOW_S, duration * 0.4);
    double outroWin = Math.min(OUTRO_WINDOW_S, duration * 0.3);

    double introEnd = detectIntroEnd(source, introWin);
    Double outroStart = detectOutroStart(source, duration, outroWin);

    ctx.put("intro_end_ts", introEnd);
    log.info("Intro ends: {}s", String.format("%.2f", introEnd));

    if (outroStart != null) {
      ctx.put("outro_start_ts", outroStart);
      log.info("Outro starts: {}s", String.format("%.2f", outroStart));
    } else {
      log.info("No outro detected.");
    }

    return StageResult.ok(name(), elapsed(t));
  }

  private double detectIntroEnd(String source, double windowSecs) {
    List<Double> sceneCuts = getSceneCuts(source, windowSecs);
    log.info("Scene cuts in first {}s: {}", String.format("%.0f", windowSecs), sceneCuts);

    for (double cut : sceneCuts) {
      if (cut >= MIN_INTRO_LENGTH_S) {
        log.info("Intro ends at scene cut: {}s", String.format("%.2f", cut));
        return cut;
      }
    }

    double fallback = windowSecs * 0.3;
    log.info(
        "No scene cut ≥{}s: heuristic: {}s", MIN_INTRO_LENGTH_S, String.format("%.2f", fallback));
    return fallback;
  }

  private List<Double> getSceneCuts(String source, double windowSecs) {
    FfmpegUtil.ProcessOutput out =
        FfmpegUtil.run(
            "ffmpeg",
            "-i",
            source,
            "-t",
            String.format("%.3f", windowSecs),
            "-vf",
            "select='gt(scene," + SCENE_THRESHOLD + ")',showinfo",
            "-an",
            "-f",
            "null",
            "-");

    List<Double> cuts = new ArrayList<>();
    Pattern p = Pattern.compile("pts_time:([\\d.]+)");
    Matcher m = p.matcher(out.stderr());
    while (m.find()) {
      try {
        cuts.add(Double.parseDouble(m.group(1)));
      } catch (NumberFormatException ignored) {
      }
    }
    return cuts;
  }

  private Double detectOutroStart(String source, double totalDuration, double windowSecs) {
    double windowStart = totalDuration - windowSecs;
    List<double[]> silences = getSilences(source, windowStart, windowSecs);
    for (double[] s : silences) {
      if (s[1] >= OUTRO_MIN_SILENCE_S) return s[0];
    }
    return null;
  }

  private List<double[]> getSilences(String source, double startSecs, double durationSecs) {
    FfmpegUtil.ProcessOutput out =
        FfmpegUtil.run(
            "ffmpeg",
            "-ss",
            String.format("%.3f", startSecs),
            "-t",
            String.format("%.3f", durationSecs),
            "-i",
            source,
            "-af",
            "silencedetect=noise=" + NOISE_FLOOR + ":d=" + OUTRO_MIN_SILENCE_S,
            "-f",
            "null",
            "-");

    List<double[]> silences = new ArrayList<>();
    String output = out.stdout() + "\n" + out.stderr();
    Pattern startPat = Pattern.compile("silence_start:\\s*([\\d.]+)");
    Pattern durPat = Pattern.compile("silence_duration:\\s*([\\d.]+)");
    Matcher sm = startPat.matcher(output);
    Matcher dm = durPat.matcher(output);
    while (sm.find() && dm.find()) {
      try {
        double silenceStart = Double.parseDouble(sm.group(1)) + startSecs;
        double silenceDur = Double.parseDouble(dm.group(1));
        silences.add(new double[] {silenceStart, silenceDur});
      } catch (NumberFormatException ignored) {
      }
    }
    return silences;
  }

  private double getDouble(PipelineContext ctx, String key) {
    Object v = ctx.get(key);
    if (v instanceof Double d) return d;
    if (v instanceof Number n) return n.doubleValue();
    return -1;
  }
}
