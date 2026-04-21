package mediaPipeline.stage.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.util.FfmpegUtil;

public class CreditRoller extends BaseStage {

    private static final double CREDITS_LOUDNESS_MARGIN = 10.0;
    private static final double MIN_CREDITS_TAIL_S      = 5.0;

    @Override
    public String name() { return "CreditRoller"; }

    @Override
    protected StageResult run(PipelineContext ctx) {
        long   t        = System.currentTimeMillis();
        double duration = getDouble(ctx, "duration");

        if (duration <= 0) {
            return StageResult.fail(name(),
                    "duration missing from context — FormatValidator must run first", elapsed(t));
        }

        String source       = ctx.video().sourcePath().toAbsolutePath().toString();
        Double creditsStart = detectCreditsStart(source, duration);

        if (creditsStart != null) {
            ctx.put("credits_start_ts", creditsStart);
            log.info("Credits start at: {}s (of {}s total)",
                    String.format("%.2f", creditsStart),
                    String.format("%.2f", duration));
        } else {
            log.info("No credits detected — dialogue continues to end of video.");
        }

        return StageResult.ok(name(), elapsed(t));
    }

    private Double detectCreditsStart(String source, double duration) {
        List<double[]> loudness = getLoudness(source);
        if (loudness.size() < 20) {
            log.info("Loudness samples too sparse ({}) — cannot detect credits.", loudness.size());
            return null;
        }

        double baseline = medianLoudness(loudness, duration * 0.3, duration * 0.7);
        if (Double.isNaN(baseline)) return null;

        double quietThreshold = baseline - CREDITS_LOUDNESS_MARGIN;
        log.info("Baseline loudness (middle 40%): {} LUFS | quiet threshold: {} LUFS",
                String.format("%.1f", baseline),
                String.format("%.1f", quietThreshold));

        double lastDialogueTs = -1;
        for (int i = loudness.size() - 1; i >= 0; i--) {
            if (loudness.get(i)[1] > quietThreshold) {
                lastDialogueTs = loudness.get(i)[0];
                break;
            }
        }

        if (lastDialogueTs < 0) return null;

        double tail = duration - lastDialogueTs;
        if (tail < MIN_CREDITS_TAIL_S) {
            log.info("No credits: dialogue reaches {}s of {}s (quiet tail {}s < {}s required)",
                    String.format("%.2f", lastDialogueTs),
                    String.format("%.2f", duration),
                    String.format("%.2f", tail),
                    MIN_CREDITS_TAIL_S);
            return null;
        }

        return lastDialogueTs;
    }

    private double medianLoudness(List<double[]> loudness, double from, double to) {
        List<Double> window = new ArrayList<>();
        for (double[] s : loudness) {
            if (s[0] >= from && s[0] <= to) window.add(s[1]);
        }
        if (window.isEmpty()) return Double.NaN;
        Collections.sort(window);
        return window.get(window.size() / 2);
    }

    private List<double[]> getLoudness(String source) {
        FfmpegUtil.ProcessOutput out = FfmpegUtil.run(
                "ffmpeg", "-i", source,
                "-af", "ebur128=peak=true",
                "-f", "null", "-"
        );

        List<double[]> points = new ArrayList<>();
        Pattern p = Pattern.compile("t:\\s*([\\d.]+).*?M:\\s*([\\-\\d.inf]+)");
        Matcher m = p.matcher(out.stderr());
        while (m.find()) {
            try {
                double ts   = Double.parseDouble(m.group(1));
                double lufs = m.group(2).contains("inf") ? -120.0 : Double.parseDouble(m.group(2));
                points.add(new double[]{ts, lufs});
            } catch (NumberFormatException ignored) {}
        }
        return points;
    }

    private double getDouble(PipelineContext ctx, String key) {
        Object v = ctx.get(key);
        if (v instanceof Double d) return d;
        if (v instanceof Number n) return n.doubleValue();
        return -1;
    }
}