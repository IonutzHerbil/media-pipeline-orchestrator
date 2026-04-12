package mediaPipeline.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class PipelineConfig {

    private static final Properties props = new Properties();

    static {
        try (InputStream is = Files.newInputStream(Path.of("pipeline.properties"))) {
            props.load(is);
        } catch (IOException ignored) {}
    }

    private PipelineConfig() {}

    public static String scriptsDir() {
        return props.getProperty("workers.scripts.dir", "../media-pipeline-workers/scripts");
    }
}