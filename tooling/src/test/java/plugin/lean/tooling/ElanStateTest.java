package plugin.lean.tooling;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ElanStateTest {
  @TempDir Path temporary;
  private ElanInstallation installation;

  @BeforeEach void prepareElan() throws IOException {
    var executable = Files.createFile(temporary.resolve("elan"));
    assertTrue(executable.toFile().setExecutable(true));
    installation = ElanInstallation.discover(Map.of(), temporary, Optional.of(executable), false);
  }

  private String state(Object active, List<?> installed) {
    return state(active, installed, Map.of());
  }

  private String state(Object active, List<?> installed, Map<String, ?> fields) {
    var toolchains = new LinkedHashMap<String, Object>();
    toolchains.put("installed", installed);
    toolchains.put("resolved_active", active);
    toolchains.putAll(fields);
    var state = Map.of("elan_version", Map.of("current", "4.2.1"), "toolchains", toolchains);
    return new GsonBuilder().serializeNulls().create().toJson(state);
  }

  private Map<String, Object> resolution(Map<String, String> live, String cached) {
    var result = new LinkedHashMap<String, Object>();
    result.put("live", live);
    result.put("cached", cached);
    return result;
  }

  private Map<String, String> installed(String name) {
    return Map.of("resolved_name", name, "path", temporary.resolve("arbitrary linked directory").toString());
  }

  @Test void readsIdentitiesAndPathsFromElanRatherThanConstructingThem() {
    var active = resolution(Map.of("Ok", "my-local-build"), null);
    var inventory = ElanState.parse(state(active, List.of(installed("my-local-build"))));
    var selected = installation.selectedToolchain(inventory);
    assertEquals("my-local-build", selected.toolchain().name());
    assertEquals(temporary.resolve("arbitrary linked directory"), selected.toolchain().path());
    assertEquals(ResolutionSource.LIVE, selected.source());
    assertThrows(UnsupportedOperationException.class, () -> inventory.installedToolchains().clear());
  }

  @Test void distinguishesCachedChannelResolutionFromLiveResolution() {
    var active = resolution(Map.of("Err", "offline"), "release");
    var inventory = ElanState.parse(state(active, List.of(installed("release"))));
    assertEquals(ResolutionSource.CACHE, installation.selectedToolchain(inventory).source());
    assertEquals("release", installation.selectedToolchain(inventory).toolchain().name());
  }

  @Test void reportsTheSourceAndLocationSuppliedByElan() {
    var active = resolution(Map.of("Ok", "linked"), null);
    var installed = List.of(installed("linked"));
    var reasons = Map.of(
      "ToolchainFile", ElanSelectionKind.CONFIGURATION_FILE, "LeanpkgFile", ElanSelectionKind.CONFIGURATION_FILE,
      "OverrideDB", ElanSelectionKind.DIRECTORY_OVERRIDE, "InToolchainDirectory", ElanSelectionKind.TOOLCHAIN_DIRECTORY
    );
    for (var reason : reasons.entrySet()) {
      var path = temporary.resolve("outer project").resolve(reason.getKey()).toString();
      var fields = Map.of("active_override", Map.of("reason", Map.of(reason.getKey(), path)));
      var origin = ElanState.parse(state(active, installed, fields)).selectionOrigin().orElseThrow();
      assertEquals(reason.getValue(), origin.kind());
      assertEquals(Optional.of(path), origin.location());
    }
    var fields = new LinkedHashMap<String, Object>();
    fields.put("default", Map.of("resolved", "linked"));
    fields.put("active_override", Map.of("reason", "Environment"));
    var environment = ElanState.parse(state(active, installed, fields)).selectionOrigin().orElseThrow();
    assertEquals(ElanSelectionKind.ENVIRONMENT, environment.kind());
    assertTrue(environment.location().isEmpty());
    fields.put("active_override", null);
    assertEquals(ElanSelectionKind.DEFAULT, ElanState.parse(state(active, installed, fields))
      .selectionOrigin().orElseThrow().kind());
    fields.put("default", null);
    assertTrue(ElanState.parse(state(null, installed, fields)).selectionOrigin().isEmpty());
  }

  @Test void missingOrUnrecognizedProvenanceDoesNotChangeToolchainResolution() {
    var active = resolution(Map.of("Ok", "linked"), null);
    var installed = List.of(installed("linked"));
    for (var fields : List.of(Map.<String, Object>of(), Map.of("active_override", Map.of("reason", "FutureReason")))) {
      var inventory = ElanState.parse(state(active, installed, fields));
      assertEquals("linked", installation.selectedToolchain(inventory).toolchain().name());
      assertTrue(inventory.selectionOrigin().isEmpty());
    }
  }

  @Test void doesNotInventAnActiveToolchainOrAcceptAnUninstalledCache() {
    var noSelection = ElanState.parse(state(null, List.of(installed("unrelated"))));
    assertThrows(ToolchainUnavailableException.class, () -> installation.selectedToolchain(noSelection));
    for (var active : List.of(
      resolution(Map.of("Ok", "missing"), null),
      resolution(Map.of("Err", "offline"), "missing"),
      resolution(Map.of("Err", "offline"), null)
    )) {
      var inventory = ElanState.parse(state(active, List.of(installed("unrelated"))));
      assertThrows(ToolchainUnavailableException.class, () -> installation.selectedToolchain(inventory));
    }
  }

  @Test void rejectsMalformedStateInsteadOfTreatingItAsAnEmptyInstallation() {
    for (String malformed : List.of("not json", "null", "[]", "{}", "{\"elan_version\": 2}")) {
      assertThrows(ElanProtocolException.class, () -> ElanState.parse(malformed), malformed);
    }
    var duplicates = state(null, List.of(installed("duplicate"), installed("duplicate")));
    var relativePath = state(null, List.of(Map.of("resolved_name", "linked", "path", "relative/path")));
    var conflictingResolution = state(resolution(Map.of("Ok", "name", "Err", "failure"), null), List.of());
    for (String malformed : List.of(duplicates, relativePath, conflictingResolution)) {
      assertThrows(ElanProtocolException.class, () -> ElanState.parse(malformed), malformed);
    }
  }

  @Test void offlineSelectionOffersTheRequestedRemoteChannelAndKeepsAnInstalledCachedReleaseUsable() {
    var channel = Map.of("Remote", Map.of("origin", "my-org/lean-nightly", "release", "nightly"));
    var record = Map.of("unresolved", channel, "reason", "Environment");
    var fields = new LinkedHashMap<String, Object>();
    fields.put("default", Map.of("unresolved", Map.of("Remote", Map.of("origin", "other/lean", "release", "beta"))));
    fields.put("active_override", record);
    for (String cached : new String[]{null, "previous"}) {
      var active = resolution(Map.of("Err", "network disabled"), cached);
      var snapshot = ElanState.parse(state(active, List.of(), fields));
      var error = assertThrows(ToolchainUnavailableException.class, () -> installation.selectedToolchain(snapshot));
      assertEquals(Optional.of("my-org/lean-nightly:nightly"), error.installTarget());
      assertTrue(error.getMessage().contains("network disabled"));
    }
    var active = resolution(Map.of("Err", "network disabled"), "previous");
    var cached = ElanState.parse(state(active, List.of(installed("previous")), fields));
    assertEquals(ResolutionSource.CACHE, installation.selectedToolchain(cached).source());
    fields.put("active_override", null);
    var fromDefault = ElanState.parse(state(active, List.of(), fields));
    assertEquals(Optional.of("other/lean:beta"), assertThrows(ToolchainUnavailableException.class,
      () -> installation.selectedToolchain(fromDefault)).installTarget());
  }

  @Test void aDirectToolchainDirectoryIsVerifiedOnTheHostWithoutRegisteringOrOfferingToDownloadIt() throws Exception {
    var directory = temporary.resolve("local build");
    var name = directory.toString();
    var fields = Map.of("active_override", Map.of("unresolved", Map.of("Path", Map.of("path", name))));
    var snapshot = ElanState.parse(state(resolution(Map.of("Ok", name), name), List.of(), fields));
    var bin = Files.createDirectories(directory.resolve("bin"));
    var missing = assertThrows(ToolchainUnavailableException.class, () -> installation.selectedToolchain(snapshot));
    assertTrue(missing.installTarget().isEmpty());
    assertTrue(missing.getMessage().contains(bin.resolve("lean").toString()));
    assertTrue(Files.createFile(bin.resolve("lean")).toFile().setExecutable(true));
    assertEquals(directory, installation.selectedToolchain(snapshot).toolchain().path());
    assertTrue(snapshot.installedToolchains().isEmpty());
    var windows = ElanInstallation.discover(Map.of(), temporary, Optional.of(installation.executable()), true);
    assertThrows(ToolchainUnavailableException.class, () -> windows.selectedToolchain(snapshot));
    Files.createFile(bin.resolve("lean.exe"));
    assertEquals(name, windows.selectedToolchain(snapshot).toolchain().name());
  }

  @Test void olderDumpsOfferTheExactMissingReleaseAndNeverGuessFromAStaleCache() {
    var missing = ElanState.parse(state(resolution(Map.of("Ok", "pinned"), null), List.of()));
    var missingError = assertThrows(ToolchainUnavailableException.class, () -> installation.selectedToolchain(missing));
    assertEquals("pinned", missingError.installTarget().orElseThrow());
    var stale = ElanState.parse(state(resolution(Map.of("Err", "offline"), "old"), List.of()));
    var staleError = assertThrows(ToolchainUnavailableException.class, () -> installation.selectedToolchain(stale));
    assertTrue(staleError.installTarget().isEmpty());
  }
}
