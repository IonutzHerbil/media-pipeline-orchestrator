package mediaPipeline.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class FfmpegUtil {

  private FfmpegUtil() {}

  public static ProcessOutput run(String... cmd) {
    try {
      ProcessBuilder pb = new ProcessBuilder(cmd);
      Process proc = pb.start();

      CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() ->
              new BufferedReader(new InputStreamReader(proc.getInputStream()))
                      .lines().collect(Collectors.joining("\n"))
      );

      CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() ->
              new BufferedReader(new InputStreamReader(proc.getErrorStream()))
                      .lines().collect(Collectors.joining("\n"))
      );

      int exit = proc.waitFor();
      String stdout = stdoutFuture.get();
      String stderr = stderrFuture.get();

      return new ProcessOutput(exit, stdout, stderr);
    } catch (Exception e) {
      return new ProcessOutput(-1, "", e.getMessage());
    }
  }

  public record ProcessOutput(int exitCode, String stdout, String stderr) {
    public boolean ok() {
      return exitCode == 0;
    }
  }
}
