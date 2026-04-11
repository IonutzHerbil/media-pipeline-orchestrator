package mediaPipeline.stage.visuals;

import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.util.FfmpegUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SpriteGenerator extends BaseStage {

    private static final int TARGET_THUMBNAILS = 50;
    private static final int THUMB_WIDTH       = 160;
    private static final int SPRITE_COLUMNS    = 10;

    @Override
    public String name() { return "SpriteGenerator"; }

    @Override
    protected StageResult run(PipelineContext ctx) {
        long   t        = System.currentTimeMillis();
        String source   = ctx.video().sourcePath().toAbsolutePath().toString();
        double duration = getDouble(ctx, "duration");

        if (duration <= 0)
            return StageResult.fail(name(), "duration missing — FormatValidator must run first", elapsed(t));

        int    interval      = Math.max(1, (int) Math.ceil(duration / TARGET_THUMBNAILS));
        int    expectedCount = (int) Math.ceil(duration / interval);
        int    rows          = (int) Math.ceil((double) expectedCount / SPRITE_COLUMNS);

        Path thumbDir   = ctx.outputRoot().resolve("images").resolve("thumbnails");
        Path spriteFile = ctx.outputRoot().resolve("images").resolve("sprite_map.jpg");

        log.info("Duration: {}s | interval: {}s | ~{} thumbnails | grid: {}x{}",
                String.format("%.1f", duration), interval, expectedCount, SPRITE_COLUMNS, rows);

        try {
            Files.createDirectories(thumbDir);
        } catch (IOException e) {
            return StageResult.fail(name(), "Could not create thumbnails dir: " + e.getMessage(), elapsed(t));
        }

        FfmpegUtil.ProcessOutput thumbResult = FfmpegUtil.run(
                "ffmpeg", "-y",
                "-i", source,
                "-vf", "fps=1/" + interval + ",scale=" + THUMB_WIDTH + ":-1",
                "-q:v", "5",
                thumbDir.toAbsolutePath() + "\\thumb_%04d.jpg"
        );

        if (!thumbResult.ok())
            return StageResult.fail(name(), "Thumbnail extraction failed: " + thumbResult.stderr(), elapsed(t));

        long actualCount;
        try {
            actualCount = Files.list(thumbDir)
                    .filter(p -> p.toString().endsWith(".jpg"))
                    .count();
        } catch (IOException e) {
            return StageResult.fail(name(), "Could not count thumbnails: " + e.getMessage(), elapsed(t));
        }

        if (actualCount == 0)
            return StageResult.fail(name(), "No thumbnails were generated", elapsed(t));

        log.info("Extracted {} thumbnails", actualCount);

        int actualRows = (int) Math.ceil((double) actualCount / SPRITE_COLUMNS);

        FfmpegUtil.ProcessOutput spriteResult = FfmpegUtil.run(
                "ffmpeg", "-y",
                "-i", thumbDir.toAbsolutePath() + "\\thumb_%04d.jpg",
                "-filter_complex", "tile=" + SPRITE_COLUMNS + "x" + actualRows,
                "-frames:v", "1",
                spriteFile.toAbsolutePath().toString()
        );

        if (!spriteResult.ok())
            return StageResult.fail(name(), "Sprite map generation failed: " + spriteResult.stderr(), elapsed(t));

        ctx.put("sprite_map_path",    spriteFile.toAbsolutePath().toString());
        ctx.put("sprite_columns",     SPRITE_COLUMNS);
        ctx.put("sprite_rows",        actualRows);
        ctx.put("thumb_interval_s",   interval);
        ctx.put("thumbnail_count",    (int) actualCount);

        log.info("Sprite map → {} ({}x{} grid, {} thumbs)", spriteFile, SPRITE_COLUMNS, actualRows, actualCount);
        return StageResult.ok(name(), elapsed(t));
    }

    private double getDouble(PipelineContext ctx, String key) {
        Object v = ctx.get(key);
        if (v instanceof Double d)  return d;
        if (v instanceof Number n)  return n.doubleValue();
        return -1;
    }
}