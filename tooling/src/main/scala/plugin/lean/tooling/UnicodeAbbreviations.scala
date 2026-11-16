package plugin.lean.tooling

import java.util.{List as JList, Locale, Map as JMap, Optional, OptionalInt}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Text and an optional explicit UTF-16 cursor position, independent of any editor. */
final case class UnicodeReplacement(text: String, cursor: OptionalInt) {
  require(cursor.isEmpty || (cursor.getAsInt >= 0 && cursor.getAsInt <= text.length), "Invalid cursor offset")
  def caretOffset: Int = cursor.orElse(text.length)
  def followedBy(suffix: String): UnicodeReplacement = copy(text = text + suffix)
}

final case class UnicodeAbbreviation(abbreviation: String, replacement: UnicodeReplacement)

/** One matched source range and every abbreviation producing its complete text. Offsets are UTF-16. */
final class UnicodeSymbolMatch private[tooling](
  val start: Int, val text: String, entries: Vector[UnicodeAbbreviation]
) {
  def end: Int = start + text.length
  val abbreviations: JList[UnicodeAbbreviation] = JList.copyOf(entries.asJava)
}

/** Immutable catalog. Equal-length prefix matches retain the input map's iteration order. */
final class UnicodeAbbreviations(translations: JMap[String, String]) {
  private val marker = "$CURSOR"
  private val entries = translations.asScala.toVector.map { case (abbreviation, template) =>
    require(abbreviation.nonEmpty, "An abbreviation cannot be empty")
    require(template.nonEmpty, "An abbreviation's replacement cannot be empty")
    val index = template.indexOf(marker)
    require(index < 0 || template.indexOf(marker, index + marker.length) < 0, "Only one cursor marker is allowed")
    val cursor = if index < 0 then OptionalInt.empty() else OptionalInt.of(index)
    UnicodeAbbreviation(abbreviation, UnicodeReplacement(template.replace(marker, ""), cursor))
  }.sortBy(_.abbreviation.length)

  val maximumLength: Int = entries.map(_.abbreviation.length).maxOption.getOrElse(0)
  private val symbols = entries.groupBy(_.replacement.text).toVector
    .filter(_._1.nonEmpty)

  def matches(prefix: String): JList[UnicodeAbbreviation] = {
    JList.copyOf(entries.filter(_.abbreviation.startsWith(prefix)).asJava)
  }

  def canContinue(prefix: String): Boolean = entries.exists(_.abbreviation.startsWith(prefix))

  /** Discovery matches aliases without case sensitivity and replacement text exactly. */
  def search(query: String): JList[UnicodeAbbreviation] = {
    val folded = query.toLowerCase(Locale.ROOT)
    val matching = entries.filter { entry =>
      entry.abbreviation.toLowerCase(Locale.ROOT).contains(folded) || entry.replacement.text.contains(query)
    }
    val ordered = if query.isEmpty then {
      matching.sortBy(_.abbreviation)
    } else {
      matching.sortBy { entry =>
        val alias = entry.abbreviation
        val priority =
          if alias == query then 0
          else if entry.replacement.text == query then 1
          else if alias.startsWith(query) then 2
          else if alias.toLowerCase(Locale.ROOT) == folded then 3
          else if alias.toLowerCase(Locale.ROOT).startsWith(folded) then 4
          else 5
        (priority, alias.length, alias)
      }
    }
    JList.copyOf(ordered.asJava)
  }

  /** Longest symbol containing the offset; ties prefer the leftmost occurrence, then text order. */
  def symbolAt(text: String, offset: Int): Optional[UnicodeSymbolMatch] = {
    if offset < 0 || offset >= text.length then Optional.empty()
    else {
      symbols.iterator.flatMap { case (symbol, aliases) =>
        val first = (offset - symbol.length + 1).max(0)
        val last = offset.min(text.length - symbol.length)
        (first to last).find(start => text.regionMatches(start, symbol, 0, symbol.length)).map { start =>
          new UnicodeSymbolMatch(start, symbol, aliases)
        }
      }.minByOption(found => (-found.text.length, found.start, found.text)).toJava
    }
  }

  def isUniqueAndComplete(abbreviation: String): Boolean = {
    val matching = entries.filter(_.abbreviation.startsWith(abbreviation))
    matching.size == 1 && matching.head.abbreviation == abbreviation
  }

  /** Resolve the longest nonempty prefix, using the shortest matching alias, then preserve the suffix. */
  def replacement(abbreviation: String): Optional[UnicodeReplacement] = {
    (abbreviation.length to 1 by -1).iterator.flatMap { length =>
      entries.find(_.abbreviation.startsWith(abbreviation.take(length))).map { entry =>
        entry.replacement.followedBy(abbreviation.drop(length))
      }
    }.nextOption().toJava
  }
}
