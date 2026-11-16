package plugin.lean.tooling;

import org.junit.jupiter.api.Test;
import plugin.lean.tooling.lsp.ObservedLspProcess;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObservedLspProcessTest {
  private static byte[] frame(String body) {
    var content = body.getBytes(StandardCharsets.UTF_8);
    var header = "Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n" +
      "Content-Length: " + content.length + "\r\n\r\n";
    return (header + body).getBytes(StandardCharsets.UTF_8);
  }

  @Test void preservesBytesAndUnicodeAcrossArbitraryReadAndWriteBoundaries() throws Exception {
    var messages = List.of("{\"text\":\"∀ n : ℕ, 𝒜 n\"}", "{\"result\":null}", "{}");
    var wire = new ByteArrayOutputStream();
    for (var message : messages) wire.write(frame(message));
    for (int chunk = 1; chunk <= 127; chunk += 7) {
      var process = new MemoryProcess(wire.toByteArray());
      var received = new ArrayList<String>();
      var sent = new ArrayList<String>();
      var observed = new ObservedLspProcess(
        process,
        bytes -> received.add(new String(bytes, StandardCharsets.UTF_8)),
        bytes -> sent.add(new String(bytes, StandardCharsets.UTF_8))
      );
      var copied = new ByteArrayOutputStream();
      var buffer = new byte[chunk];
      int count;
      while ((count = observed.getInputStream().read(buffer)) >= 0) {
        copied.write(buffer, 0, count);
        observed.getOutputStream().write(buffer, 0, count);
      }
      assertEquals(messages, received);
      assertEquals(messages, sent);
      assertArrayEquals(wire.toByteArray(), copied.toByteArray());
      assertArrayEquals(wire.toByteArray(), process.output.toByteArray());
    }
  }

  @Test void supportsSingleByteReadsAndSkipsWithoutLosingEnvelopes() throws Exception {
    var payload = frame("{\"a\":1}");
    var received = new ArrayList<byte[]>();
    var observed = new ObservedLspProcess(new MemoryProcess(payload), received::add, bytes -> {});
    assertEquals(payload[0], observed.getInputStream().read());
    assertEquals(payload.length - 1, observed.getInputStream().skip(payload.length * 2L));
    assertEquals(-1, observed.getInputStream().read());
    assertEquals(1, received.size());
    assertEquals("{\"a\":1}", new String(received.getFirst(), StandardCharsets.UTF_8));
  }

  @Test void rejectsMalformedAndTruncatedFramesAtTheTransportBoundary() {
    for (var wire : List.of(
      "Content-Length: 3\r\n\r\n{}",
      "Content-Length: 1\r\nContent-Length: 1\r\n\r\nx",
      "Content-Length: -1\r\n\r\n",
      "Content-Length: unknown\r\n\r\n",
      "Content-Type: application/json\r\n\r\n{}",
      "Content-Length: 2\r\n"
    )) {
      var process = new MemoryProcess(wire.getBytes(StandardCharsets.UTF_8));
      var observed = new ObservedLspProcess(process, bytes -> {}, bytes -> {});
      assertThrows(IOException.class, () -> observed.getInputStream().readAllBytes(), wire);
    }
  }

  private static final class MemoryProcess extends Process {
    private final InputStream input;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private MemoryProcess(byte[] incoming) { input = new ByteArrayInputStream(incoming); }
    @Override public InputStream getInputStream() { return input; }
    @Override public OutputStream getOutputStream() { return output; }
    @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    @Override public int waitFor() { return 0; }
    @Override public int exitValue() { return 0; }
    @Override public void destroy() {}
  }
}
