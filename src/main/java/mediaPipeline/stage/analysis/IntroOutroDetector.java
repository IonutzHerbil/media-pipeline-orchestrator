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
    private static final String NOISE_FLOOR    = "-30dB";
    private static final double MIN_SILENCE_S  = 0.5;
    private static final double LONG_SILENCE_S = 1.5;

    @Override
    public String name() { return "IntroOutroDetector"; }

    @Override
    protected StageResult run(PipelineContext ctx) {
        long t          = System.currentTimeMillis();
        double duration = getDouble(ctx, "duration");

        if (duration <= 0) {
            return StageResult.fail(name(),
                    "duration missing from context — FormatValidator must run first", elapsed(t));
        }

        String source     = ctx.video().sourcePath().toAbsolutePath().toString();
        double introWin   = Math.min(INTRO_WINDOW_S, duration * 0.3);
        double outroWin   = Math.min(OUTRO_WINDOW_S, duration * 0.3);

        double introEnd   = detectIntroEnd(source, introWin);
        double outroStart = detectOutroStart(source, duration, outroWin);

        ctx.put("intro_end_ts",   introEnd);
        ctx.put("outro_start_ts", outroStart);

        log.info("Intro ends: {}s | Outro starts: {}s",
                String.format("%.2f", introEnd),
                String.format("%.2f", outroStart));

        return StageResult.ok(name(), elapsed(t));
    }

    private double detectIntroEnd(String source, double windowSecs) {
        List<double[]> silences = getSilences(source, 0.0, windowSecs);
        if (silences.isEmpty()) {
            log.info("No silence in intro window — heuristic: {}s",
                    String.format("%.2f", windowSecs));
            return windowSecs;
        }

        for (double[] s : silences) {
            if (s[1] >= LONG_SILENCE_S) {
                log.info("Intro ends at long silence: {}s", String.format("%.2f", s[0]));
                return s[0];
            }
        }

        double[] last = silences.get(silences.size() - 1);
        log.info("Intro ends at last silence in window: {}s", String.format("%.2f", last[0]));
        return last[0];
    }

    private double detectOutroStart(String source, double totalDuration, double windowSecs) {
        double windowStart = totalDuration - windowSecs;
        List<double[]> silences = getSilences(source, windowStart, windowSecs);
        if (silences.isEmpty()) {
            double fallback = totalDuration - windowSecs;
            log.info("No silence in outro window — heuristic: {}s",
                    String.format("%.2f", fallback));
            return fallback;
        }

        for (double[] s : silences) {
            if (s[1] >= LONG_SILENCE_S) {
                log.info("Outro starts at long silence: {}s", String.format("%.2f", s[0]));
                return s[0];
            }
        }

        double[] first = silences.get(0);
        log.info("Outro starts at first silence in window: {}s",
                String.format("%.2f", first[0]));
        return first[0];
    }

    private List<double[]> getSilences(String source, double startSecs, double durationSecs) {
        FfmpegUtil.ProcessOutput out = FfmpegUtil.run(
                "ffmpeg",
                "-ss", String.format("%.3f", startSecs),
                "-t",  String.format("%.3f", durationSecs),
                "-i",  source,
                "-af", "silencedetect=noise=" + NOISE_FLOOR + ":d=" + MIN_SILENCE_S,
                "-f",  "null",
                "-"
        );

        List<double[]> silences = new ArrayList<>();
        String output = out.stdout() + "\n" + out.stderr();

        Pattern startPat = Pattern.compile("silence_start:\\s*([\\d.]+)");
        Pattern durPat   = Pattern.compile("silence_duration:\\s*([\\d.]+)");

        Matcher sm = startPat.matcher(output);
        Matcher dm = durPat.matcher(output);

        while (sm.find() && dm.find()) {
            try {
                double silenceStart    = Double.parseDouble(sm.group(1)) + startSecs;
                double silenceDuration = Double.parseDouble(dm.group(1));
                silences.add(new double[]{silenceStart, silenceDuration});
            } catch (NumberFormatException ignored) {}
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