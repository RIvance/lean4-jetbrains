package plugin.lean.tooling;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ElanStateTest {
  @TempDir Path temporary;

  private String state(Object active, List<?> installed) {
    var toolchains = new LinkedHashMap<String, Object>();
    toolchains.put("installed", installed);
    toolchains.put("resolved_active", active);
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
    var selected = inventory.selectedToolchain();
    assertEquals("my-local-build", selected.toolchain().name());
    assertEquals(temporary.resolve("arbitrary linked directory"), selected.toolchain().path());
    assertEquals(ResolutionSource.LIVE, selected.source());
    assertThrows(UnsupportedOperationException.class, () -> inventory.installedToolchains().clear());
  }

  @Test void distinguishesCachedChannelResolutionFromLiveResolution() {
    var active = resolution(Map.of("Err", "offline"), "release");
    var inventory = ElanState.parse(state(active, List.of(installed("release"))));
    assertEquals(ResolutionSource.CACHE, inventory.selectedToolchain().source());
    assertEquals("release", inventory.selectedToolchain().toolchain().name());
  }

  @Test void doesNotInventAnActiveToolchainOrAcceptAnUninstalledCache() {
    var noSelection = ElanState.parse(state(null, List.of(installed("unrelated"))));
    assertThrows(ToolchainUnavailableException.class, noSelection::selectedToolchain);
    for (var active : List.of(
      resolution(Map.of("Ok", "missing"), null),
      resolution(Map.of("Err", "offline"), "missing"),
      resolution(Map.of("Err", "offline"), null)
    )) {
      var inventory = ElanState.parse(state(active, List.of(installed("unrelated"))));
      assertThrows(ToolchainUnavailableException.class, inventory::selectedToolchain);
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
}
