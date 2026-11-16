package plugin.lean.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LeanWorkspaceTest {
  @TempDir Path temporary;

  @Test void choosesTheNearestNestedProjectAndRecognizesBothLakefileFormats() throws Exception {
    Files.writeString(temporary.resolve("lakefile.lean"), "");
    Path nested = Files.createDirectories(temporary.resolve("packages/nested"));
    Files.writeString(nested.resolve("lakefile.toml"), "");
    var workspace = LeanWorkspace.discover(nested.resolve("src/Main.lean"), temporary);
    assertEquals(nested, workspace.root());
    assertTrue(workspace.usesLake());
    assertEquals(workspace, LeanWorkspace.discoverDirectory(nested, temporary));
    assertEquals(workspace, LeanWorkspace.discoverDirectory(nested.resolve("src"), temporary));
  }

  @Test void aToolchainBoundarySeparatesStandaloneFilesFromAnEnclosingLakeProject() throws Exception {
    Files.writeString(temporary.resolve("lakefile.toml"), "");
    Path nested = Files.createDirectories(temporary.resolve("example"));
    Files.writeString(nested.resolve("lean-toolchain"), "local");
    var workspace = LeanWorkspace.discover(nested.resolve("Main.lean"), temporary);
    assertEquals(nested, workspace.root());
    assertFalse(workspace.usesLake());
  }

  @Test void standaloneFilesUseTheirContainingProjectOrDirectory() {
    Path project = temporary.resolve("project");
    assertEquals(project, LeanWorkspace.discover(project.resolve("src/Main.lean"), project).root());
    assertEquals(temporary, LeanWorkspace.discover(temporary.resolve("Other.lean"), project).root());
  }

  @Test void serverOptionsRemainLiteralOrderedArgumentsForBothLaunchModes() {
    var options = new ArrayList<>(List.of("-j", "2", "-Dtrace.Meta.synthInstance=true", "a value with spaces", ""));
    for (boolean usesLake : List.of(false, true)) {
      var workspace = new LeanWorkspace(temporary, usesLake);
      var expected = new ArrayList<>(usesLake
        ? List.of("lake", "serve", "--", temporary.toString())
        : List.of("lean", "--server", temporary.toString()));
      expected.addAll(options);
      var arguments = workspace.serverArguments(options);
      assertEquals(expected, arguments);
      assertThrows(UnsupportedOperationException.class, () -> arguments.add("changed"));
      options.set(1, "4");
      assertEquals(expected, arguments);
      options.set(1, "2");
    }
  }
}
