package plugin.lean.tooling;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UnicodeAbbreviationsTest {
  @Test
  void resolvesPrefixesInDeclaredOrderAndRetainsUnknownSuffixes() {
    var translations = new LinkedHashMap<String, String>();
    translations.put("alpha", "α");
    translations.put("alpine", "mountain");
    translations.put("alto", "first");
    translations.put("alfa", "second");
    var catalog = new UnicodeAbbreviations(translations);
    assertEquals("first", catalog.replacement("al").orElseThrow().text());
    assertEquals("α7", catalog.replacement("alph7").orElseThrow().text());
    assertEquals(2, catalog.replacement("alph7").orElseThrow().caretOffset());
    assertTrue(catalog.replacement("").isEmpty());
    assertTrue(catalog.replacement("?").isEmpty());
    assertTrue(catalog.isUniqueAndComplete("alpha"));
    assertFalse(catalog.isUniqueAndComplete("alp"));
    translations.clear();
    assertEquals("α", catalog.replacement("alpha").orElseThrow().text());
    assertThrows(UnsupportedOperationException.class, () -> catalog.matches("al").clear());
  }

  @Test
  void keepsExplicitCursorPositionsDistinctFromTheEndOfTheReplacement() {
    var catalog = new UnicodeAbbreviations(Map.of("pair", "𝔸$CURSOR⟩", "end", "x$CURSOR"));
    var pair = catalog.replacement("pair!").orElseThrow();
    assertEquals("𝔸⟩!", pair.text());
    assertEquals(2, pair.caretOffset());
    assertEquals(1, catalog.replacement("end!").orElseThrow().caretOffset());
    assertThrows(IllegalArgumentException.class, () -> new UnicodeAbbreviations(Map.of("", "a")));
    assertThrows(IllegalArgumentException.class, () -> new UnicodeAbbreviations(Map.of("a", "$CURSOR$CURSOR")));
  }

  @Test
  void discoversAliasesAndFindsTheLongestSymbolContainingAUtf16Offset() {
    var catalog = new UnicodeAbbreviations(Map.of(
      "alpha", "α", "Alph", "α", "double", "αα", "fancy", "𝔸", "pair", "⟨$CURSOR⟩"
    ));
    assertEquals(2, catalog.search("ALPH").size());
    assertEquals(3, catalog.search("α").size());
    var matched = catalog.symbolAt("xααy", 2).orElseThrow();
    assertEquals("αα", matched.text());
    assertEquals(1, matched.start());
    assertEquals(3, matched.end());
    assertEquals("double", matched.abbreviations().getFirst().abbreviation());
    assertEquals("𝔸", catalog.symbolAt("x𝔸y", 2).orElseThrow().text());
    assertEquals("⟨⟩", catalog.symbolAt("⟨⟩", 1).orElseThrow().text());
    assertTrue(catalog.symbolAt("", 0).isEmpty());
    assertTrue(catalog.symbolAt("α", -1).isEmpty());
    assertTrue(catalog.symbolAt("α", 1).isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> matched.abbreviations().clear());
  }

  @Test
  void resolvesEquallyLongOverlappingSymbolsByTheirSourcePosition() {
    var catalog = new UnicodeAbbreviations(Map.of("one", "ab", "two", "ba"));
    assertEquals("ba", catalog.symbolAt("bab", 1).orElseThrow().text());
  }

  @Test
  void discoveryRanksExactAliasesBeforeCaseInsensitiveMatches() {
    var catalog = new UnicodeAbbreviations(Map.of("Alpha", "Α", "alpha", "α", "alphas", "αs"));
    assertEquals("alpha", catalog.search("alpha").getFirst().abbreviation());
    assertEquals("Alpha", catalog.search("Alpha").getFirst().abbreviation());
    assertEquals("alpha", catalog.search("α").getFirst().abbreviation());
  }
}
