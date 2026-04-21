package mediaPipeline.util;

import java.util.ArrayList;
import java.util.List;

public class PythonRunner {

  private PythonRunner() {}

  public static FfmpegUtil.ProcessOutput run(String script, String... args) {
    List<String> cmd = new ArrayList<>(List.of(PipelineConfig.pythonExecutable(), script));
    cmd.addAll(List.of(args));
    return FfmpegUtil.run(cmd.toArray(new String[0]));
  }
}
