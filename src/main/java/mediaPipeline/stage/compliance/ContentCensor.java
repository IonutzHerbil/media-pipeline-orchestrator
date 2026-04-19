package mediaPipeline.stage.compliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.util.FfmpegUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ContentCensor extends BaseStage {

    @Override
    public String name() { return "ContentCensor"; }

    @Override
    protected StageResult run(PipelineContext ctx) {
        long t = System.currentTimeMillis();

        Path reportPath = ctx.outputRoot().resolve("metadata/safety_report.json");
        if (!reportPath.toFile().exists())
            return StageResult.fail(name(), "safety_report.json missing: SafetyScanner must run first", elapsed(t));

        JsonNode report;
        try {
            report = new ObjectMapper().readTree(reportPath.toFile());
        } catch (IOException e) {
            return StageResult.fail(name(), "Could not read safety_report.json: " + e.getMessage(), elapsed(t));
        }

        JsonNode flags = report.get("flags");
        if (flags == null || flags.isEmpty()) {
            log.info("No flags — censoring not needed.");
            return StageResult.ok(name(), elapsed(t));
        }

        List<String> bleepFilters  = new ArrayList<>();
        List<String> blurFilters   = new ArrayList<>();

        for (JsonNode flag : flags) {
            String action   = flag.path("action").asText();
            String category = flag.path("category").asText();
            double start    = parseTimestamp(flag.path("start").asText());
            double end      = parseTimestamp(flag.path("end").asText());

            if ("bleep_audio".equals(action)) {
                bleepFilters.add(String.format(
                        "volume=enable='between(t,%.3f,%.3f)':volume=0", start, end));
            } else if ("EPILEPSY_RISK".equals(category)) {
                blurFilters.add(String.format(
                        "boxblur=10:enable='between(t,%.3f,%.3f)'", start, end));
            }
        }

        if (bleepFilters.isEmpty() && blurFilters.isEmpty()) {
            log.info("No actionable flags — skipping.");
            return StageResult.ok(name(), elapsed(t));
        }

        @SuppressWarnings("unchecked")
        List<String> encodedAssets = (List<String>) ctx.get("encoded_assets");
        if (encodedAssets == null || encodedAssets.isEmpty())
            return StageResult.fail(name(), "encoded_assets missing: Transcoder must run first", elapsed(t));

        String primary = encodedAssets.stream()
                .filter(p -> p.contains("720p") && p.contains("h264"))
                .findFirst()
                .orElse(encodedAssets.get(0));

        Path output = ctx.outputRoot().resolve("video/h264/720p_h264_censored.mp4");

        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-y", "-i", primary));

        if (!bleepFilters.isEmpty())
            cmd.addAll(List.of("-af", String.join(",", bleepFilters)));

        if (!blurFilters.isEmpty())
            cmd.addAll(List.of("-vf", String.join(",", blurFilters),
                    "-c:v", "libx264", "-preset", "fast", "-crf", "23"));
        else
            cmd.addAll(List.of("-c:v", "copy"));

        cmd.addAll(List.of("-c:a", "aac", "-b:a", "128k",
                output.toAbsolutePath().toString()));

        FfmpegUtil.ProcessOutput result = FfmpegUtil.run(cmd.toArray(new String[0]));

        if (!result.ok())
            return StageResult.fail(name(), "Censoring failed: " + result.stderr(), elapsed(t));

        ctx.put("censored_video", output.toAbsolutePath().toString());
        log.info("Censored video → {} ({} bleeps, {} blurs)",
                output.getFileName(), bleepFilters.size(), blurFilters.size());
        return StageResult.ok(name(), elapsed(t));
    }

    private double parseTimestamp(String ts) {
        try {
            String[] parts = ts.replace(",", ".").split(":");
            if (parts.length == 3)
                return Double.parseDouble(parts[0]) * 3600
                        + Double.parseDouble(parts[1]) * 60
                        + Double.parseDouble(parts[2]);
            if (parts.length == 2)
                return Double.parseDouble(parts[0]) * 60
                        + Double.parseDouble(parts[1]);
            return Double.parseDouble(parts[0]);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}