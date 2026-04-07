package mediaPipeline.model;

import java.nio.file.Path;

public record VideoFile(String movieId, Path sourcePath) {}
