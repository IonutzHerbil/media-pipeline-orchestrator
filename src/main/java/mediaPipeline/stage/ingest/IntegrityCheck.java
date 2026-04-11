package mediaPipeline.stage.ingest;

import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

public class IntegrityCheck extends BaseStage {

  private static final long MIN_FILE_SIZE_BYTES = 1024L;
  private static final int  HEADER_BYTES        = 12;

  private static final Map<String, int[]> SIGNATURES =
      Map.of(
          "MP4", new int[] {4, 'f', 't', 'y', 'p'},
          "MKV", new int[] {0, 0x1A, 0x45, 0xDF, 0xA3},
          "WEBM", new int[] {0, 0x1A, 0x45, 0xDF, 0xA3}
      );

  @Override
  public String name() {
    return "IntegrityCheck";
  }

  @Override
  protected StageResult run(PipelineContext ctx) {
    long t    = System.currentTimeMillis();
    Path path = ctx.video().sourcePath().toAbsolutePath();

    if (!path.toFile().exists())
      return StageResult.fail(name(), "File not found: " + path, elapsed(t));

    if (!path.toFile().canRead())
      return StageResult.fail(name(), "File not readable: " + path, elapsed(t));

    long fileSize;
    try {
      fileSize = Files.size(path);
    } catch (IOException e) {
      return StageResult.fail(name(), "Could not read file size: " + e.getMessage(), elapsed(t));
    }

    if (fileSize < MIN_FILE_SIZE_BYTES)
      return StageResult.fail(name(), "File too small (" + fileSize + " bytes) — likely corrupt.", elapsed(t));

    log.info("File size: {} bytes ({} MB)", fileSize, String.format("%.2f", fileSize / (1024.0 * 1024.0)));
    ctx.put("file_size", fileSize);

    byte[] header;
    try {
      header = readHeader(path, HEADER_BYTES);
    } catch (IOException e) {
      return StageResult.fail(name(), "Could not read file header: " + e.getMessage(), elapsed(t));
    }

    String containerFormat = detectContainer(header);
    if ("UNKNOWN".equals(containerFormat))
      log.warn("Unrecognised container magic bytes — FormatValidator will do a deeper check.");
    else
      log.info("Detected container: {}", containerFormat);

    ctx.put("container_format", containerFormat);

    String checksum;
    try {
      checksum = sha256Hex(path);
    } catch (IOException | NoSuchAlgorithmException e) {
      return StageResult.fail(name(), "Checksum failed: " + e.getMessage(), elapsed(t));
    }

    log.info("SHA-256: {}", checksum);
    ctx.put("source_checksum", checksum);

    return StageResult.ok(name(), elapsed(t));
  }

  private String detectContainer(byte[] h) {
    for (Map.Entry<String, int[]> entry : SIGNATURES.entrySet()) {
      int[] sig    = entry.getValue();
      int   offset = sig[0];
      boolean match = true;
      for (int i = 1; i < sig.length; i++) {
        if (offset + (i - 1) >= h.length || (h[offset + (i - 1)] & 0xFF) != sig[i]) {
          match = false;
          break;
        }
      }
      if (match) return entry.getKey();
    }
    return "UNKNOWN";
  }

  private byte[] readHeader(Path path, int n) throws IOException {
    try (InputStream is = Files.newInputStream(path)) {
      return is.readNBytes(n);
    }
  }

  private String sha256Hex(Path path) throws IOException, NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] buf = new byte[64 * 1024];
    try (InputStream is = Files.newInputStream(path)) {
      int read;
      while ((read = is.read(buf)) != -1)
        digest.update(buf, 0, read);
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}