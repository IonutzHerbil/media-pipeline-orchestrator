package mediaPipeline.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

public class FfmpegUtil {

  private FfmpegUtil() {}

  public static ProcessOutput run(String... cmd) {
    try {
      ProcessBuilder pb = new ProcessBuilder(cmd);
      Process proc = pb.start();

      String stdout =
          new BufferedReader(new InputStreamReader(proc.getInputStream()))
              .lines()
              .collect(Collectors.joining("\n"));
      String stderr =
          new BufferedReader(new InputStreamReader(proc.getErrorStream()))
              .lines()
              .collect(Collectors.joining("\n"));

      int exit = proc.waitFor();
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
