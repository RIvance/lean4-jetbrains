package plugin.lean.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LeanSourceRootsTest {
  @TempDir Path directory;

  @Test
  void resolvesRelativePathsWithoutLosingSearchOrderOrUnicode() throws Exception {
    var roots = LeanSourceRoots.parse("[\"lib α\",\"../dependency\",\"lib α/../lib α\",\"\"]", directory);
    assertEquals(List.of(
      directory.resolve("lib α"), directory.getParent().resolve("dependency"), directory
    ), roots.paths());
    assertThrows(UnsupportedOperationException.class, () -> roots.paths().clear());
  }

  @Test
  void preservesAbsoluteSourceRoots() throws Exception {
    String encoded = new com.google.gson.Gson().toJson(List.of(directory.toString()));
    assertEquals(List.of(directory), LeanSourceRoots.parse(encoded, directory.resolve("nested")).paths());
  }

  @Test
  void rejectsInvalidResponsesInsteadOfPublishingPartialRoots() {
    for (String json : List.of("{}", "null", "[1]", "[\"valid\",null]", "[\"\\u0000\"]", "[")) {
      assertThrows(IOException.class, () -> LeanSourceRoots.parse(json, directory), json);
    }
  }
}
