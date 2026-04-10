package mediaPipeline.stage.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.util.FfmpegUtil;

public class CreditRoller extends BaseStage {

    private static final double CREDITS_WINDOW_RATIO = 0.20;
    private static final double MAX_CREDITS_WINDOW_S = 300.0;
    private static final double FALLBACK_RATIO        = 0.92;
    private static final double LONG_SILENCE_S        = 2.0;
    private static final String NOISE_FLOOR           = "-30dB";
    private static final double MIN_SILENCE_S         = 0.5;

    @Override
    public String name() { return "CreditRoller"; }

    @Override
    protected StageResult run(PipelineContext ctx) {
        long t          = System.currentTimeMillis();
        double duration = getDouble(ctx, "duration");

        if (duration <= 0) {
            return StageResult.fail(name(),
                    "duration missing from context — FormatValidator must run first", elapsed(t));
        }

        String source    = ctx.video().sourcePath().toAbsolutePath().toString();
        double window    = Math.min(duration * CREDITS_WINDOW_RATIO, MAX_CREDITS_WINDOW_S);
        double scanStart = duration - window;

        double creditsStart = detectCreditsStart(source, scanStart, window, duration);

        log.info("Credits start at: {}s (duration: {}s)",
                String.format("%.2f", creditsStart),
                String.format("%.2f", duration));

        ctx.put("credits_start_ts", creditsStart);
        return StageResult.ok(name(), elapsed(t));
    }

    private double detectCreditsStart(
            String source, double scanStart, double window, double duration) {

        FfmpegUtil.ProcessOutput out = FfmpegUtil.run(
                "ffmpeg",
                "-ss", String.format("%.3f", scanStart),
                "-t",  String.format("%.3f", window),
                "-i",  source,
                "-af", "silencedetect=noise=" + NOISE_FLOOR + ":d=" + MIN_SILENCE_S,
                "-f",  "null",
                "-"
        );

        List<double[]> silences = parseSilences(out.stdout() + "\n" + out.stderr(), scanStart);

        if (silences.isEmpty()) {
            return fallback(duration);
        }

        for (double[] s : silences) {
            if (s[1] >= LONG_SILENCE_S) {
                log.info("Credits start at long silence: {}s", String.format("%.2f", s[0]));
                return s[0];
            }
        }

        double[] first = silences.get(0);
        log.info("Credits start at first silence: {}s", String.format("%.2f", first[0]));
        return first[0];
    }

    private List<double[]> parseSilences(String output, double offset) {
        List<double[]> silences = new ArrayList<>();

        Pattern startPat = Pattern.compile("silence_start:\\s*([\\d.]+)");
        Pattern durPat   = Pattern.compile("silence_duration:\\s*([\\d.]+)");

        Matcher sm = startPat.matcher(output);
        Matcher dm = durPat.matcher(output);

        while (sm.find() && dm.find()) {
            try {
                double silenceStart    = Double.parseDouble(sm.group(1)) + offset;
                double silenceDuration = Double.parseDouble(dm.group(1));
                silences.add(new double[]{silenceStart, silenceDuration});
            } catch (NumberFormatException ignored) {}
        }
        return silences;
    }

    private double fallback(double duration) {
        double ts = duration * FALLBACK_RATIO;
        log.info("Credit detection inconclusive — heuristic fallback: {}s",
                String.format("%.2f", ts));
        return ts;
    }

    private double getDouble(PipelineContext ctx, String key) {
        Object v = ctx.get(key);
        if (v instanceof Double d) return d;
        if (v instanceof Number n) return n.doubleValue();
        return -1;
    }
}