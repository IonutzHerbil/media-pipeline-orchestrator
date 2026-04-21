package mediaPipeline.stage.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.util.FfmpegUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SafetyScanner extends BaseStage {

    private enum Category {
        PROFANITY, HATE_SPEECH, VIOLENCE, SELF_HARM, DRUGS, EPILEPSY_RISK
    }

    private static final Map<Category, Set<String>> WORDLISTS = Map.of(
            Category.PROFANITY, Set.of(
                    "fuck", "fucking", "fucked", "fucker", "shit", "bitch",
                    "bastard", "asshole", "cunt", "dick", "pussy", "crap",
                    "pula", "pule", "muie", "pizda", "futut", "futi",
                    "dracu", "dreacu", "dracului", "cacat", "curva",
                    "nenorocit", "prost", "proasta", "bou", "boule", "naiba"
            ),
            Category.HATE_SPEECH, Set.of(
                    "nigger", "nigga", "faggot", "retard", "spastic",
                    "tigan", "tigane", "jidan", "bozgor"
            ),
            Category.VIOLENCE, Set.of(
                    "kill", "murder", "stab", "shoot", "bomb", "explode", "attack",
                    "omor", "ucide", "impusca", "atac", "bomba"
            ),
            Category.SELF_HARM, Set.of(
                    "suicide", "kill myself", "self harm", "cut myself",
                    "sinucidere", "ma omor"
            ),
            Category.DRUGS, Set.of(
                    "cocaine", "heroin", "meth", "weed", "marijuana",
                    "cocaina", "heroina", "droguri", "iarbă"
            )
    );

    private static final Pattern TIMESTAMP_LINE = Pattern.compile(
            "^\\[(.+?) --> (.+?)\\]\\s+(.+)$"
    );
    private static final double FLASH_DIFF_THRESHOLD  = 60.0;
    private static final int    FLASH_COUNT_THRESHOLD = 3;
    private static final double FLASH_WINDOW_SECS     = 1.0;

    @Override
    public String name() { return "SafetyScanner"; }

    @Override
    protected StageResult run(PipelineContext ctx) {
        long t = System.currentTimeMillis();

        String transcriptPath = ctx.getString("transcript_path");
        if (transcriptPath == null)
            return StageResult.fail(name(),
                    "transcript_path missing — SpeechToText must run first", elapsed(t));

        Path transcript = Path.of(transcriptPath);
        if (!Files.exists(transcript))
            return StageResult.fail(name(), "Transcript not found: " + transcriptPath, elapsed(t));

        List<Map<String, Object>> flags = new ArrayList<>();

        try {
            for (String line : Files.readAllLines(transcript)) {
                Matcher m = TIMESTAMP_LINE.matcher(line.trim());
                if (!m.matches()) continue;
                String start = m.group(1);
                String end   = m.group(2);
                String text  = m.group(3);
                for (Map.Entry<Category, Set<String>> entry : WORDLISTS.entrySet())
                    flags.addAll(scan(start, end, text, entry.getValue(), entry.getKey()));
            }
        } catch (IOException e) {
            return StageResult.fail(name(),
                    "Could not read transcript: " + e.getMessage(), elapsed(t));
        }

        flags.addAll(detectFlashes(ctx.video().sourcePath().toAbsolutePath().toString()));

        Map<String, Long> summary = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            long count = flags.stream().filter(f -> f.get("category").equals(c.name())).count();
            summary.put(c.name(), count);
        }

        Path report = ctx.outputRoot().resolve("metadata/safety_report.json");
        try {
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                    report.toFile(),
                    Map.of(
                            "transcript",  transcriptPath,
                            "total_flags", flags.size(),
                            "summary",     summary,
                            "flags",       flags
                    )
            );
        } catch (IOException e) {
            return StageResult.fail(name(),
                    "Could not write safety_report.json: " + e.getMessage(), elapsed(t));
        }

        ctx.put("safety_flags", flags);
        log.info("Safety scan — {} flags: {}", flags.size(), summary);
        return StageResult.ok(name(), elapsed(t));
    }

    private List<Map<String, Object>> detectFlashes(String source) {
        List<Map<String, Object>> flags = new ArrayList<>();

        FfmpegUtil.ProcessOutput out = FfmpegUtil.run(
                "ffmpeg", "-i", source,
                "-vf", "scale=160:90,signalstats,metadata=print:file=-",
                "-f", "null", "-"
        );

        List<double[]> frames = parseLuminance(out.stdout() + "\n" + out.stderr());
        if (frames.size() < 4) return flags;

        List<double[]> transitions = new ArrayList<>();
        for (int i = 1; i < frames.size(); i++) {
            double delta = frames.get(i)[1] - frames.get(i - 1)[1];
            if (Math.abs(delta) >= FLASH_DIFF_THRESHOLD) {
                transitions.add(new double[]{frames.get(i)[0], Math.signum(delta)});
            }
        }

        for (int i = 0; i < transitions.size(); i++) {
            double windowStart = transitions.get(i)[0];
            int j = i, ups = 0, downs = 0;
            while (j < transitions.size()
                    && transitions.get(j)[0] - windowStart <= FLASH_WINDOW_SECS) {
                if (transitions.get(j)[1] > 0) ups++; else downs++;
                j++;
            }
            int count = ups + downs;
            if (count >= FLASH_COUNT_THRESHOLD && ups >= 1 && downs >= 1) {
                double windowEnd = transitions.get(j - 1)[0];
                flags.add(Map.of(
                        "start",    String.format("%.2f", windowStart),
                        "end",      String.format("%.2f", windowEnd + 0.1),
                        "category", Category.EPILEPSY_RISK.name(),
                        "action",   "review",
                        "context",  count + " alternating luminance swings in "
                                + FLASH_WINDOW_SECS + "s (Harding threshold: "
                                + FLASH_COUNT_THRESHOLD + ")"
                ));
                i = j - 1;
            }
        }

        return flags;
    }

    private List<double[]> parseLuminance(String output) {
        List<double[]> frames = new ArrayList<>();
        Pattern tsPat   = Pattern.compile("pts_time:(-?[\\d.]+)");
        Pattern yavgPat = Pattern.compile("lavfi\\.signalstats\\.YAVG=([\\d.]+)");

        double currentTs = -1;
        for (String line : output.split("\\r?\\n")) {
            Matcher tm = tsPat.matcher(line);
            if (tm.find()) {
                try { currentTs = Double.parseDouble(tm.group(1)); }
                catch (NumberFormatException ignored) {}
                continue;
            }
            Matcher ym = yavgPat.matcher(line);
            if (ym.find() && currentTs >= 0) {
                try {
                    frames.add(new double[]{currentTs, Double.parseDouble(ym.group(1))});
                } catch (NumberFormatException ignored) {}
            }
        }
        return frames;
    }

    private List<Map<String, Object>> scan(String start, String end, String text,
                                           Set<String> wordlist, Category category) {
        List<Map<String, Object>> found = new ArrayList<>();
        String lower = text.toLowerCase();
        for (String phrase : wordlist) {
            Pattern p = Pattern.compile(
                    "(?<![\\w])" + Pattern.quote(phrase) + "(?![\\w])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            );
            if (p.matcher(lower).find()) {
                found.add(Map.of(
                        "start",    start,
                        "end",      end,
                        "word",     phrase,
                        "category", category.name(),
                        "action",   category == Category.PROFANITY ? "bleep_audio" : "review",
                        "context",  text.length() > 60 ? text.substring(0, 60) + "..." : text
                ));
            }
        }
        return found;
    }
}