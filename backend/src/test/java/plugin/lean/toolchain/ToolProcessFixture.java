package plugin.lean.toolchain;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** A child JVM keeps process tests independent of shells and installed toolchains. */
public final class ToolProcessFixture {
  public static void main(String[] arguments) throws Exception {
    System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
    Files.writeString(Path.of(arguments[0]), Long.toString(ProcessHandle.current().pid()));
    System.out.println("ready");
    if (arguments[1].equals("wait")) {
      System.in.read();
    } else {
      System.out.println("α output");
      System.err.println("process diagnostic");
      System.exit(Integer.parseInt(arguments[1]));
    }
  }
}
