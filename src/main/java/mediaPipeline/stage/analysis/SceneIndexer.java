package mediaPipeline.stage.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.util.FfmpegUtil;

public class SceneIndexer extends BaseStage {

    private static final int COMPLEXITY_LOW  = 500;
    private static final int COMPLEXITY_HIGH = 3000;
    private static final int CRF_HIGH        = 18;
    private static final int CRF_MEDIUM      = 23;
    private static final int CRF_LOW         = 26;

    @Override
    public String name() { return "SceneIndexer"; }

    @Override
    protected StageResult run(PipelineContext ctx) {
        long t          = System.currentTimeMillis();
        String source   = ctx.video().sourcePath().toAbsolutePath().toString();
        Path outputPath = ctx.outputRoot().resolve("metadata").resolve("scene_analysis.json");

        double duration = getDouble(ctx, "duration");
        if (duration <= 0) {
            return StageResult.fail(name(), "duration missing from context", elapsed(t));
        }

        FfmpegUtil.ProcessOutput probe = FfmpegUtil.run(
                "ffprobe",
                "-v", "error",
                "-select_streams", "v",
                "-show_entries", "packet=pts_time,size,flags",
                "-of", "csv=p=0",
                source
        );

        if (!probe.ok()) {
            return StageResult.fail(name(),
                    "ffprobe failed: " + probe.stderr(), elapsed(t));

        }

        List<double[]> packets = parsePackets(probe.stdout());
        if (packets.isEmpty()) {
            return StageResult.fail(name(), "No packets parsed from ffprobe output", elapsed(t));
        }
        List<double[]> ranges = buildRanges(packets, duration);
        log.info("Detected {} scenes via keyframe analysis", ranges.size());

        List<Map<String, Object>> sceneList = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            double start = ranges.get(i)[0];
            double end   = ranges.get(i)[1];
            int avg = computeAvgPacketSize(packets, start, end);

            String level;
            int    crf;
            if (avg < COMPLEXITY_LOW)       { level = "low";    crf = CRF_LOW;    }
            else if (avg > COMPLEXITY_HIGH) { level = "high";   crf = CRF_HIGH;   }
            else                            { level = "medium"; crf = CRF_MEDIUM; }

            String sceneType = switch (level) {
                case "high" -> "action";
                case "low"  -> "establishing_shot";
                default     -> "dialogue";
            };

            sceneList.add(Map.of(
                    "index",      i,
                    "start",      round3(start),
                    "end",        round3(end),
                    "duration",   round3(end - start),
                    "scene_type", sceneType,
                    "complexity", Map.of(
                            "avg_packet_size", avg,
                            "level",           level,
                            "suggested_crf",   crf,
                            "frame_count",     countPackets(packets, start, end)
                    )
            ));
        }

        double avgPkt = sceneList.stream()
                .mapToInt(s -> (int) ((Map<?,?>) s.get("complexity")).get("avg_packet_size"))
                .average().orElse(0);

        Map<String, Object> result = Map.of(
                "source",      source,
                "scene_count", sceneList.size(),
                "summary", Map.of(
                        "avg_packet_size",       (int) avgPkt,
                        "complexity_thresholds", Map.of(
                                "low_bytes",  COMPLEXITY_LOW,
                                "high_bytes", COMPLEXITY_HIGH
                        )
                ),
                "scenes", sceneList
        );

        try {
            new ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(outputPath.toFile(), result);
        } catch (IOException e) {
            return StageResult.fail(name(),
                    "Failed to write scene_analysis.json: " + e.getMessage(), elapsed(t));
        }

        ctx.put("scene_analysis_path", outputPath.toAbsolutePath().toString());
        log.info("scene_analysis.json → {} ({} scenes)", outputPath, sceneList.size());

        return StageResult.ok(name(), elapsed(t));
    }

    private List<double[]> parsePackets(String stdout) {
        List<double[]> packets = new ArrayList<>();
        for (String line : stdout.split("\\r?\\n")) {
            String[] parts = line.strip().split(",");
            if (parts.length != 3) continue;
            try {
                double ts    = Double.parseDouble(parts[0].trim());
                double size  = Double.parseDouble(parts[1].trim());
                double isKey = parts[2].trim().contains("K") ? 1.0 : 0.0;
                packets.add(new double[]{ts, size, isKey});
            } catch (NumberFormatException ignored) {}
        }
        return packets;
    }

    private List<double[]> buildRanges(List<double[]> packets, double duration) {
        List<double[]> ranges = new ArrayList<>();
        double prev = 0.0;
        for (double[] pkt : packets) {
            if (pkt[2] == 1.0 && pkt[0] > 0.0) {
                ranges.add(new double[]{prev, pkt[0]});
                prev = pkt[0];
            }
        }
        ranges.add(new double[]{prev, duration});
        return ranges;
    }

    private int computeAvgPacketSize(List<double[]> packets, double start, double end) {
        return (int) packets.stream()
                .filter(p -> p[0] >= start && p[0] < end)
                .mapToDouble(p -> p[1])
                .average()
                .orElse(0);
    }

    private int countPackets(List<double[]> packets, double start, double end) {
        return (int) packets.stream()
                .filter(p -> p[0] >= start && p[0] < end)
                .count();
    }

    private double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private double getDouble(PipelineContext ctx, String key) {
        Object v = ctx.get(key);
        if (v instanceof Double d) return d;
        if (v instanceof Number n) return n.doubleValue();
        return -1;
    }
}