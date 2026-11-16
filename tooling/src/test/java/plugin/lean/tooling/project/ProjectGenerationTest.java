package plugin.lean.tooling.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectGenerationTest {
  @TempDir Path temporary;

  @Test void publishesACompleteDirectoryTree() throws Exception {
    Path target = temporary.resolve("new project");
    ProjectGeneration.generate(target, staging -> {
      write(staging.resolve("Example/Basic.lean"), "def answer := 42");
      write(staging.resolve("lakefile.toml"), "name = \"example\"");
    });
    assertEquals("def answer := 42", Files.readString(target.resolve("Example/Basic.lean")));
    assertEquals("name = \"example\"", Files.readString(target.resolve("lakefile.toml")));
    assertOnlyDestinationRemains(target);
  }

  @Test void preservesExistingUnrelatedFiles() throws Exception {
    Path target = temporary.resolve("project");
    write(target.resolve(".idea/workspace.xml"), "existing IDE metadata");
    ProjectGeneration.generate(target, staging -> write(staging.resolve("Main.lean"), "def main := pure ()"));
    assertEquals("existing IDE metadata", Files.readString(target.resolve(".idea/workspace.xml")));
    assertEquals("def main := pure ()", Files.readString(target.resolve("Main.lean")));
    assertOnlyDestinationRemains(target);
  }

  @Test void aConflictPreventsPublicationOfEveryGeneratedFile() throws Exception {
    Path target = temporary.resolve("project");
    write(target.resolve("Main.lean"), "existing source");
    var error = assertThrows(ProjectFileConflictException.class, () -> {
      ProjectGeneration.generate(target, staging -> {
        write(staging.resolve("New.lean"), "new source");
        write(staging.resolve("Main.lean"), "replacement");
      });
    });
    assertEquals(List.of(target.resolve("Main.lean")), error.paths());
    assertEquals("existing source", Files.readString(target.resolve("Main.lean")));
    assertFalse(Files.exists(target.resolve("New.lean")));
    assertOnlyDestinationRemains(target);
  }

  @Test void generationFailureLeavesTheDestinationUnchanged() throws Exception {
    Path target = temporary.resolve("project");
    write(target.resolve("notes.txt"), "keep");
    var failure = new UncheckedIOException(new IOException("generation failed"));
    var thrown = assertThrows(UncheckedIOException.class, () -> {
      ProjectGeneration.generate(target, staging -> {
        write(staging.resolve("Partial.lean"), "partial");
        throw failure;
      });
    });
    assertSame(failure, thrown);
    assertEquals("keep", Files.readString(target.resolve("notes.txt")));
    assertFalse(Files.exists(target.resolve("Partial.lean")));
    assertOnlyDestinationRemains(target);
  }

  @Test void cancellationBeforePublicationDoesNotCreateTheDestination() throws Exception {
    Path target = temporary.resolve("project");
    try {
      assertThrows(InterruptedException.class, () -> {
        ProjectGeneration.generate(target, staging -> {
          write(staging.resolve("Main.lean"), "generated source");
          Thread.currentThread().interrupt();
        });
      });
    } finally {
      Thread.interrupted();
    }
    assertFalse(Files.exists(target));
    try (var entries = Files.list(temporary)) {
      assertEquals(0, entries.count());
    }
  }

  private void assertOnlyDestinationRemains(Path target) throws IOException {
    try (var entries = Files.list(temporary)) {
      assertEquals(List.of(target), entries.toList());
    }
  }

  private static void write(Path path, String text) {
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, text);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
