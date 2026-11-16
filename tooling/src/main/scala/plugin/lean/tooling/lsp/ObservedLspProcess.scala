package plugin.lean.tooling.lsp

import java.io.{ByteArrayOutputStream, FilterInputStream, FilterOutputStream, IOException, InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CompletableFuture, TimeUnit}
import java.util.function.Consumer

/**
 * Observes complete LSP envelopes without consuming, rewriting, or scheduling the process's bytes.
 * Each callback receives its own byte array. Callbacks run on the stream's reader/writer thread;
 * they must not block or write back to that stream. Outgoing envelopes are observed before delivery.
 *
 * The IDE's typed LSP client discards extension fields on standard messages. Observing the transport
 * preserves those fields for Lean's InfoView while the IDE continues to own the LSP connection.
 */
final class ObservedLspProcess(
  process: Process,
  received: Consumer[Array[Byte]],
  sent: Consumer[Array[Byte]]
) extends Process {
  private val input = new FilterInputStream(process.getInputStream) {
    private val decoder = new LspMessageDecoder(received)

    override def read(): Int = {
      val value = in.read()
      if value < 0 then decoder.finish() else decoder.accept(value.toByte)
      value
    }

    override def read(bytes: Array[Byte], offset: Int, length: Int): Int = {
      val count = in.read(bytes, offset, length)
      if count < 0 then decoder.finish()
      else decoder.accept(bytes, offset, count)
      count
    }

    override def skip(count: Long): Long = {
      // Skipping must still observe messages, just as reading and discarding would.
      val buffer = new Array[Byte](8192)
      var remaining = count.max(0)
      while (remaining > 0) {
        val size = read(buffer, 0, remaining.min(buffer.length).toInt)
        if size < 0 then return count.max(0) - remaining
        remaining -= size
      }
      count.max(0)
    }

    override def markSupported(): Boolean = false
    override def mark(limit: Int): Unit = ()
    override def reset(): Unit = throw new IOException("An observed LSP stream cannot be rewound")
  }

  private val output = new FilterOutputStream(process.getOutputStream) {
    private val decoder = new LspMessageDecoder(sent)

    override def write(value: Int): Unit = {
      decoder.accept(value.toByte)
      out.write(value)
    }

    override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = {
      decoder.accept(bytes, offset, length)
      out.write(bytes, offset, length)
    }
  }

  override def getInputStream: InputStream = input
  override def getOutputStream: OutputStream = output
  override def getErrorStream: InputStream = process.getErrorStream
  override def waitFor(): Int = process.waitFor()
  override def waitFor(timeout: Long, unit: TimeUnit): Boolean = process.waitFor(timeout, unit)
  override def exitValue(): Int = process.exitValue()
  override def destroy(): Unit = process.destroy()
  override def destroyForcibly(): Process = { process.destroyForcibly(); this }
  override def isAlive: Boolean = process.isAlive
  override def pid(): Long = process.pid()
  override def toHandle: ProcessHandle = process.toHandle
  override def supportsNormalTermination(): Boolean = process.supportsNormalTermination()
  override def onExit(): CompletableFuture[Process] = process.onExit().thenApply(_ => this)
}

/** Byte framing, independent of JSON decoding. Content-Length measures UTF-8 bytes, not characters. */
private final class LspMessageDecoder(consume: Consumer[Array[Byte]]) {
  private val header = new ByteArrayOutputStream()
  private var tail = 0
  private var body: Option[Array[Byte]] = None
  private var position = 0

  def accept(bytes: Array[Byte], offset: Int, length: Int): Unit = {
    java.util.Objects.checkFromIndexSize(offset, length, bytes.length)
    var index = offset
    while (index < offset + length) {
      body match {
        case None => {
          accept(bytes(index))
          index += 1
        }
        case Some(buffer) => {
          val count = (buffer.length - position).min(offset + length - index)
          System.arraycopy(bytes, index, buffer, position, count)
          position += count
          index += count
          if position == buffer.length then complete(buffer)
        }
      }
    }
  }

  def accept(value: Byte): Unit = {
    body match {
      case Some(buffer) => {
        buffer(position) = value
        position += 1
        if position == buffer.length then complete(buffer)
      }
      case None => {
        header.write(value.toInt)
        tail = (tail << 8) | (value & 0xff)
        if tail == 0x0d0a0d0a then {
          val buffer = new Array[Byte](contentLength())
          header.reset()
          tail = 0
          body = Some(buffer)
          if buffer.isEmpty then complete(buffer)
        }
      }
    }
  }

  def finish(): Unit = {
    if body.nonEmpty || header.size() != 0 then throw new IOException("Truncated LSP message")
  }

  private def contentLength(): Int = {
    val values = header.toString(StandardCharsets.US_ASCII).split("\r\n").toList.flatMap { line =>
      val separator = line.indexOf(':')
      if separator >= 0 && line.take(separator).equalsIgnoreCase("Content-Length") then
        Some(line.drop(separator + 1).trim)
      else None
    }
    values match {
      case value :: Nil => value.toIntOption.filter(_ >= 0).getOrElse {
        throw new IOException("Invalid LSP Content-Length")
      }
      case _ => throw new IOException("An LSP message must have exactly one Content-Length header")
    }
  }

  private def complete(buffer: Array[Byte]): Unit = {
    body = None
    position = 0
    consume.accept(buffer)
  }
}
