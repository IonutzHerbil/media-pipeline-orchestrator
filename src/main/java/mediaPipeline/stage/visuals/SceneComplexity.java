package mediaPipeline.stage.visuals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;

public class SceneComplexity extends BaseStage {

    @Override
    public String name() { return "SceneComplexity"; }

    @Override
    protected StageResult run(PipelineContext ctx) {
        long t = System.currentTimeMillis();

        String analysisPath = ctx.getString("scene_analysis_path");
        if (analysisPath == null) {
            return StageResult.fail(name(),
                    "scene_analysis_path missing — SceneIndexer must run first",
                    elapsed(t));
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(new File(analysisPath));
        } catch (IOException e) {
            return StageResult.fail(name(),
                    "Failed to read scene_analysis.json: " + e.getMessage(),
                    elapsed(t));
        }

        JsonNode scenes = root.get("scenes");
        if (scenes == null || !scenes.isArray() || scenes.isEmpty()) {
            return StageResult.fail(name(),
                    "scene_analysis.json has no scenes", elapsed(t));
        }

        List<Integer> crfValues  = new ArrayList<>();
        StringBuilder profileLog = new StringBuilder();

        for (JsonNode scene : scenes) {
            int    index      = scene.get("index").asInt();
            double start      = scene.get("start").asDouble();
            double end        = scene.get("end").asDouble();
            JsonNode complexity = scene.get("complexity");

            int    crf   = complexity.get("suggested_crf").asInt();
            String level = complexity.get("level").asText();
            int    avgPkt = complexity.get("avg_packet_size").asInt();

            crfValues.add(crf);
            profileLog.append(String.format(
                    "  scene %d [%.2f-%.2f] → %s (avg_pkt=%d, crf=%d)%n",
                    index, start, end, level, avgPkt, crf));
        }

        log.info("Complexity profile:\n{}", profileLog);
        int crf18 = (int) crfValues.stream().filter(c -> c == 18).count();
        int crf23 = (int) crfValues.stream().filter(c -> c == 23).count();
        int crf26 = (int) crfValues.stream().filter(c -> c == 26).count();

        int dominantCrf;
        if (crf18 >= crf23 && crf18 >= crf26)      dominantCrf = 18;
        else if (crf23 >= crf18 && crf23 >= crf26) dominantCrf = 23;
        else                                        dominantCrf = 26;

        double avgCrf = crfValues.stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(23);

        log.info("CRF distribution → 18:{} 23:{} 26:{} | dominant:{} avg:{}",
                crf18, crf23, crf26, dominantCrf, String.format("%.1f", avgCrf));
        ctx.put("avg_crf", avgCrf);
        ctx.put("suggested_crf",      dominantCrf);
        ctx.put("complexity_profile", crfValues);

        log.info("Encoding profile → CRF {} ({} scenes analyzed)",
                dominantCrf, scenes.size());

        return StageResult.ok(name(), elapsed(t));
    }
}