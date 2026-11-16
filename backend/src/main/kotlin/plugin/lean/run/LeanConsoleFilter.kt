package plugin.lean.run

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.LazyFileHyperlinkInfo
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Recognizes Lean's console locations, optionally prefixed by Lake's log level. */
class LeanConsoleFilter(private val project: Project, private val directory: Path) : Filter, DumbAware {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val match = location.find(line) ?: return null
        val filename = checkNotNull(match.groups[1])
        val documentLine = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 }?.minus(1) ?: return null
        val documentColumn = match.groupValues[3].toIntOrNull() ?: return null
        val path = try {
            directory.resolve(filename.value).normalize()
        } catch (_: InvalidPathException) {
            return null
        }
        val hyperlink = object : LazyFileHyperlinkInfo(project, path.toString(), documentLine, documentColumn) {
            override fun calculateOffset(document: Document, documentLine: Int, documentColumn: Int): Int? {
                if (documentLine >= document.lineCount) return null
                val start = document.getLineStartOffset(documentLine)
                val end = document.getLineEndOffset(documentLine)
                val text = document.charsSequence
                // Lean console columns count Unicode scalars; IntelliJ document offsets count UTF-16 units.
                val column = documentColumn.coerceAtMost(Character.codePointCount(text, start, end))
                return Character.offsetByCodePoints(text, start, column)
            }
        }
        val start = entireLength - line.length
        return Filter.Result(start + filename.range.first, start + match.range.last, hyperlink)
    }

    companion object {
        private val location = Regex("^(?:(?:trace|info|warning|error): )?(.+?):([0-9]+):([0-9]+)(?:-[0-9]+:[0-9]+)?:")
    }
}
