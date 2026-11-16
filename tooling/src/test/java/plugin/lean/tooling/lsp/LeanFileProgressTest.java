package plugin.lean.tooling.lsp;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LeanFileProgressTest {
  @Test
  void preservesVersionPositionsAndProcessingKindsInAnImmutableSnapshot() throws Exception {
    var progress = parse("""
      {
        "textDocument": {"uri": "file:///example.lean", "version": 17},
        "processing": [
          {"range": {"start": {"line": 1, "character": 3}, "end": {"line": 2, "character": 0}}},
          {"range": {"start": {"line": 4, "character": 0}, "end": {"line": 4, "character": 0}}, "kind": 2}
        ]
      }
      """);
    assertEquals("file:///example.lean", progress.uri());
    assertEquals(17, progress.version().getAsInt());
    assertEquals(LeanProcessingKind.PROCESSING, progress.ranges().get(0).kind());
    assertEquals(new LspPosition(1, 3), progress.ranges().get(0).start());
    assertEquals(new LspPosition(2, 0), progress.ranges().get(0).end());
    assertEquals(LeanProcessingKind.FATAL_ERROR, progress.ranges().get(1).kind());
    assertThrows(UnsupportedOperationException.class, () -> progress.ranges().clear());
  }

  @Test
  void acceptsCompletionWithoutAVersionAndIgnoresAdditionalProtocolFields() throws Exception {
    for (String document : List.of(
      "{\"uri\":\"file:///example.lean\"}",
      "{\"uri\":\"file:///example.lean\",\"version\":null}"
    )) {
      var progress = parse("{\"textDocument\":" + document + ",\"processing\":[],\"extra\":true}");
      assertTrue(progress.version().isEmpty());
      assertTrue(progress.ranges().isEmpty());
    }
  }

  @Test
  void rejectsMalformedNotificationsBeforePublishingAnyRanges() {
    String valid = """
      {
        "textDocument": {"uri":"file:///example.lean", "version": 7},
        "processing": [{
          "range": {"start": {"line": 1, "character": 3}, "end": {"line": 2, "character": 0}},
          "kind": 1
        }]
      }
      """;
    for (String invalid : List.of(
      "null", "{}", "[]",
      valid.replace("\"version\": 7", "\"version\": 1.5"),
      valid.replace("\"version\": 7", "\"version\": 2147483648"),
      valid.replace("\"line\": 1", "\"line\": -1"),
      valid.replace("\"line\": 2", "\"line\": 0"),
      valid.replace("\"kind\": 1", "\"kind\": 3"),
      valid.replace("\"kind\": 1", "\"kind\": \"1\"")
    )) {
      assertThrows(IOException.class, () -> parse(invalid));
    }
  }

  private static LeanFileProgress parse(String json) throws IOException {
    return LeanFileProgress.parse(JsonParser.parseString(json));
  }
}
