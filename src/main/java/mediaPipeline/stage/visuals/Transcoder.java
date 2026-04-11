package mediaPipeline.stage.visuals;

import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;
import mediaPipeline.util.FfmpegUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Transcoder extends BaseStage {

    private enum Codec {
        H264("libx264",    "mp4",  "h264",  0),
        VP9 ("libvpx-vp9", "webm", "vp9",  +4),
        HEVC("libx265",    "mkv",  "hevc", -2);

        final String encoder;
        final String ext;
        final String folder;
        final int    crfDelta;

        Codec(String encoder, String ext, String folder, int crfDelta) {
            this.encoder  = encoder;
            this.ext      = ext;
            this.folder   = folder;
            this.crfDelta = crfDelta;
        }
    }

    private enum Resolution {
        UHD_4K ("4k",    3840),
        FHD_1080("1080p", 1920),
        HD_720  ("720p",  1280);

        final String label;
        final int    width;

        Resolution(String label, int width) {
            this.label = label;
            this.width = width;
        }
    }

    private static final int    DEFAULT_CRF   = 23;
    private static final String AUDIO_BITRATE = "128k";
    private static final String NULL_SINK     = System.getProperty("os.name")
            .toLowerCase().contains("win") ? "NUL" : "/dev/null";

    @Override
    public String name() { return "Transcoder"; }

    @Override
    protected StageResult run(PipelineContext ctx) {
        long   t        = System.currentTimeMillis();
        String source   = ctx.video().sourcePath().toAbsolutePath().toString();
        int    baseCrf  = getInt(ctx, "suggested_crf", DEFAULT_CRF);
        int    srcWidth = getInt(ctx, "width", 1920);

        log.info("Base CRF: {}  |  Source width: {}px", baseCrf, srcWidth);

        ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<CompletableFuture<EncodeResult>> futures = new ArrayList<>();

        for (Codec codec : Codec.values()) {
            for (Resolution res : Resolution.values()) {
                final Codec      c = codec;
                final Resolution r = res;
                futures.add(CompletableFuture.supplyAsync(
                        () -> encode(ctx, source, c, r, baseCrf, srcWidth), pool));
            }
        }

        List<String> encodedAssets  = new ArrayList<>();
        List<String> failedVariants = new ArrayList<>();

        for (CompletableFuture<EncodeResult> f : futures) {
            EncodeResult er = f.join();
            if (er.skipped) {
                log.info("Skipped (upscale prevented): {}", er.outputPath);
            } else if (er.success) {
                log.info("Encoded: {}", er.outputPath);
                encodedAssets.add(er.outputPath);
            } else {
                log.error("Failed: {} — {}", er.outputPath, er.error);
                failedVariants.add(er.outputPath + " (" + er.error + ")");
            }
        }

        pool.shutdown();
        ctx.put("encoded_assets", encodedAssets);

        if (!failedVariants.isEmpty())
            return StageResult.fail(name(), "Encodes failed: " + failedVariants, elapsed(t));

        log.info("Transcoder done — {} assets encoded.", encodedAssets.size());
        return StageResult.ok(name(), elapsed(t));
    }

    private EncodeResult encode(PipelineContext ctx, String source,
                                Codec codec, Resolution res, int baseCrf, int srcWidth) {
        if (srcWidth < res.width)
            return EncodeResult.skipped(buildOutputPath(ctx, codec, res));

        int    crf        = Math.max(0, baseCrf + codec.crfDelta);
        String outputPath = buildOutputPath(ctx, codec, res);
        String label      = res.label + "_" + codec.folder;

        if (codec == Codec.VP9)
            return encodeVp9TwoPass(source, outputPath, res, crf, label);

        List<String> cmd = new ArrayList<>(List.of(
                "ffmpeg", "-y",
                "-i", source,
                "-vf", "scale=" + res.width + ":-2",
                "-c:v", codec.encoder
        ));

        switch (codec) {
            case H264, HEVC -> cmd.addAll(List.of("-crf", String.valueOf(crf), "-preset", "fast",
                    "-c:a", "aac", "-b:a", AUDIO_BITRATE));
            default -> {}
        }

        cmd.add(outputPath);

        FfmpegUtil.ProcessOutput result = FfmpegUtil.run(cmd.toArray(new String[0]));
        if (!result.ok())
            return EncodeResult.failure(outputPath, result.stderr());

        return EncodeResult.success(outputPath);
    }

    private EncodeResult encodeVp9TwoPass(String source, String outputPath,
                                          Resolution res, int crf, String label) {
        String scaleFilter = "scale=" + res.width + ":-2";

        String[] pass1 = {
                "ffmpeg", "-y",
                "-i", source,
                "-vf", scaleFilter,
                "-c:v", "libvpx-vp9",
                "-crf", String.valueOf(crf), "-b:v", "0",
                "-cpu-used", "8",
                "-deadline", "good",
                "-tile-columns", "2",
                "-threads", "4",
                "-pass", "1",
                "-an",
                "-f", "null", NULL_SINK
        };

        log.info("[{}] VP9 pass 1/2...", label);
        FfmpegUtil.ProcessOutput p1 = FfmpegUtil.run(pass1);
        if (!p1.ok())
            return EncodeResult.failure(outputPath, "VP9 pass 1 failed: " + p1.stderr());

        log.info("[{}] VP9 pass 2/2...", label);

        String[] pass2 = {
                "ffmpeg", "-y",
                "-i", source,
                "-vf", scaleFilter,
                "-c:v", "libvpx-vp9",
                "-crf", String.valueOf(crf), "-b:v", "0",
                "-cpu-used", "8",
                "-deadline", "good",
                "-tile-columns", "2",
                "-threads", "4",
                "-pass", "2",
                "-c:a", "libopus", "-b:a", AUDIO_BITRATE,
                outputPath
        };

        try {
            ProcessBuilder pb = new ProcessBuilder(pass2);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            Thread progressThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    long lastLog = 0;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("frame=") && System.currentTimeMillis() - lastLog > 5000) {
                            log.info("[{}] VP9 pass 2 — {}", label, line.trim());
                            lastLog = System.currentTimeMillis();
                        }
                    }
                } catch (Exception ignored) {}
            });
            progressThread.setDaemon(true);
            progressThread.start();

            int exit = proc.waitFor();
            progressThread.join(2000);

            if (exit != 0)
                return EncodeResult.failure(outputPath, "VP9 pass 2 failed (exit " + exit + ")");

        } catch (Exception e) {
            return EncodeResult.failure(outputPath, "VP9 pass 2 exception: " + e.getMessage());
        }

        log.info("[{}] VP9 two-pass complete.", label);
        return EncodeResult.success(outputPath);
    }

    private String buildOutputPath(PipelineContext ctx, Codec codec, Resolution res) {
        Path dir = ctx.outputRoot().resolve("video").resolve(codec.folder);
        String filename = res.label + "_" + codec.folder + "." + codec.ext;
        return dir.resolve(filename).toAbsolutePath().toString();
    }

    private int getInt(PipelineContext ctx, String key, int fallback) {
        Object v = ctx.get(key);
        if (v instanceof Number n) return n.intValue();
        return fallback;
    }

    private record EncodeResult(String outputPath, boolean success, boolean skipped, String error) {
        static EncodeResult success(String path)               { return new EncodeResult(path, true,  false, null);  }
        static EncodeResult skipped(String path)               { return new EncodeResult(path, true,  true,  null);  }
        static EncodeResult failure(String path, String error) { return new EncodeResult(path, false, false, error); }
    }
}