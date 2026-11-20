package plugin.lean.tooling;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LeanReleaseIndexTest {
  @Test void readsPublishedVersionsUsingElansInstalledIdentities() {
    var releases = LeanReleaseIndex.parse("""
      {"version":"1","stable":[{"name":"v4.35.0","assets":[]},{"name":"v4.34.1"}],
       "beta":[{"name":"v4.36.0-rc1"}],"nightly":[{"name":"nightly-2026-11-01"}]}
      """);
    assertEquals(List.of("leanprover/lean4:v4.35.0", "leanprover/lean4:v4.34.1", "leanprover/lean4:v4.36.0-rc1",
      "leanprover/lean4-nightly:nightly-2026-11-01"), releases.stream().map(LeanRelease::toolchain).toList());
    assertEquals(LeanReleaseChannel.NIGHTLY, releases.get(3).channel());
  }

  @Test void distinguishesEmptyChannelsFromAnUnreadableIndex() {
    assertTrue(LeanReleaseIndex.parse("{\"version\":\"1\",\"stable\":[],\"beta\":[],\"nightly\":[]}").isEmpty());
    for (var invalid : List.of("not json", "null", "{\"version\":\"2\"}", "{\"version\":\"1\",\"stable\":[]}",
      "{\"version\":\"1\",\"stable\":[{\"name\":42}],\"beta\":[],\"nightly\":[]}")) {
      assertThrows(IOException.class, () -> LeanReleaseIndex.parse(invalid), invalid);
    }
  }
}
