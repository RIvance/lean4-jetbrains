package plugin.lean.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ElanInstallationTest {
  @TempDir Path temporary;

  private Path executable(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, "#!/bin/sh\nexit 0\n");
    assertTrue(path.toFile().setExecutable(true));
    return path;
  }

  @Test void discoversElanHomeBeforePathAndPreservesTheEnvironment() throws Exception {
    Path homeElan = executable(temporary.resolve("custom elan/bin/elan"));
    Path pathElan = executable(temporary.resolve("other/bin/elan"));
    var environment = new HashMap<>(Map.of(
      "ELAN_HOME", temporary.resolve("custom elan").toString(),
      "PATH", pathElan.getParent().toString(),
      "LEAN_PATH", "project libraries"
    ));
    var installation = ElanInstallation.discover(environment, temporary, Optional.empty(), false);
    environment.put("LEAN_PATH", "changed");
    var command = installation.inspect(temporary, Optional.empty());

    assertEquals(homeElan, installation.executable());
    assertEquals(List.of("dump-state", "--no-net"), command.argumentsList());
    assertEquals("project libraries", command.environmentMap().get("LEAN_PATH"));
    assertThrows(UnsupportedOperationException.class, () -> command.argumentsList().clear());
    assertThrows(UnsupportedOperationException.class, () -> command.environmentMap().clear());
  }

  @Test void findsSystemManagedElanWhileRespectingCustomElanHome() throws Exception {
    Path binary = executable(temporary.resolve("system tools/elan"));
    var environment = Map.of("ELAN_HOME", temporary.resolve("data").toString(), "PATH", binary.getParent().toString());
    var installation = ElanInstallation.discover(environment, temporary, Optional.empty(), false);

    assertEquals(binary, installation.executable());
    var command = installation.inspect(temporary, Optional.empty());
    assertEquals(temporary.resolve("data").toString(), command.environmentMap().get("ELAN_HOME"));
  }

  @Test void explicitExecutableIsAuthoritativeRegardlessOfSearchEnvironment() throws Exception {
    Path binary = executable(temporary.resolve(".elan/bin/elan"));
    var invalidSearchEnvironment = Map.of("ELAN_HOME", "invalid\u0000home", "PATH", "invalid\u0000path");
    var installation = ElanInstallation.discover(invalidSearchEnvironment, temporary, Optional.of(binary), false);
    assertEquals(binary, installation.executable());
    Path missing = temporary.resolve("missing/elan");
    var error = assertThrows(ElanNotFoundException.class, () -> {
      ElanInstallation.discover(invalidSearchEnvironment, temporary, Optional.of(missing), false);
    });
    assertEquals(List.of(missing), error.searchedPaths());
  }

  @Test void fallsBackToElansDefaultHomeOnlyWhenNoHomeIsConfigured() throws Exception {
    Path binary = executable(temporary.resolve(".elan/bin/elan"));
    assertEquals(binary, ElanInstallation.discover(Map.of(), temporary, Optional.empty(), false).executable());
    var environment = Map.of("ELAN_HOME", temporary.resolve("missing").toString());
    assertThrows(ElanNotFoundException.class, () -> {
      ElanInstallation.discover(environment, temporary, Optional.empty(), false);
    });
  }

  @Test void handlesWindowsEnvironmentNamesAndPathSeparators() throws Exception {
    Path binary = executable(temporary.resolve("Windows Tools/elan.exe"));
    var environment = Map.of(
      "Path", temporary.resolve("missing") + ";" + binary.getParent(), "Elan_Toolchain", "original"
    );
    var installation = ElanInstallation.discover(environment, temporary, Optional.empty(), true);
    assertEquals(binary, installation.executable());
    var selected = installation.inspect(temporary, Optional.of("selected")).environmentMap();
    assertEquals("selected", selected.get("ELAN_TOOLCHAIN"));
    assertFalse(selected.containsKey("Elan_Toolchain"));
  }

  @Test void usesArgumentsInsteadOfShellInterpolationAndKeepsSelectionsIsolated() throws Exception {
    var installation = ElanInstallation.discover(
      Map.of("ELAN_TOOLCHAIN", "original"), temporary,
      Optional.of(executable(temporary.resolve("elan"))), false
    );
    var selected = installation.inspect(temporary, Optional.of("local-build"));
    var automatic = installation.inspect(temporary, Optional.empty());
    assertEquals("local-build", selected.environmentMap().get("ELAN_TOOLCHAIN"));
    assertEquals("original", automatic.environmentMap().get("ELAN_TOOLCHAIN"));

    var workspace = new LeanWorkspace(temporary.resolve("project $with spaces"), true);
    var toolchain = new InstalledToolchain("custom-build", temporary.resolve("linked path"));
    var command = installation.run(workspace.root(), toolchain.name(), workspace.serverArguments(List.of()));
    assertEquals(workspace.root(), command.directory());
    var lakeArguments = List.of("run", "--", "custom-build", "lake", "serve", "--", workspace.root().toString());
    var leanArguments = List.of("run", "--", "custom-build", "lean", "--server", temporary.toString());
    assertEquals(lakeArguments, command.argumentsList());
    var standaloneWorkspace = new LeanWorkspace(temporary, false);
    var standalone = installation.run(temporary, toolchain.name(), standaloneWorkspace.serverArguments(List.of()));
    assertEquals(leanArguments, standalone.argumentsList());
    var installArguments = List.of("toolchain", "install", "--", "--help");
    assertEquals(installArguments, installation.install(temporary, "--help").argumentsList());
    assertThrows(IllegalArgumentException.class, () -> installation.install(temporary, ""));
  }
}
