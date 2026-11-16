package plugin.lean

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes
import plugin.lean.language.LeanColor
import plugin.lean.language.LeanLanguage
import plugin.lean.lsp.LeanSemanticTokensSupport
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.settings.LeanColorSettingsPage
import plugin.lean.settings.LeanSettings
import java.awt.Color

class LeanHighlightingTest : BasePlatformTestCase() {
    private val semanticTokenTypes = listOf(
        "keyword", "variable", "property", "function", "namespace", "type", "class", "enum",
        "interface", "struct", "typeParameter", "parameter", "enumMember", "event", "method",
        "macro", "modifier", "comment", "string", "number", "regexp", "operator", "decorator",
        "leanSorryLike", "markupText", "markupHeading", "markupQuote", "markupList",
        "markupInlineCode", "markupCodeBlock", "markupUrl", "markupCrossReference"
    )

    override fun setUp() {
        super.setUp()
        project.service<LeanSettings>().update(LeanConfiguration(enabled = false))
    }

    fun testLexicalAndSemanticStringsUseTheSameLanguageSpecificStyle() {
        myFixture.configureByText("Example.lean", "\"hello\"")
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(
            LeanLanguage, project, myFixture.file.virtualFile
        )
        val lexer = highlighter.highlightingLexer
        lexer.start(myFixture.file.text)
        assertSame(LeanColor.STRING.key, highlighter.getTokenHighlights(checkNotNull(lexer.tokenType)).single())
        val semantic = LeanSemanticTokensSupport()
        assertSame(LeanColor.STRING.key, semantic.getTextAttributesKey(SemanticTokenTypes.String, emptyList()))
        assertTrue(semantic.shouldAskServerForSemanticTokens(myFixture.file))
        myFixture.configureByText("Example.txt", "\"hello\"")
        assertFalse(semantic.shouldAskServerForSemanticTokens(myFixture.file))
    }

    fun testEverySemanticTokenKindHasAnAdvertisedAndDistinctLeanStyle() {
        val semantic = LeanSemanticTokensSupport()
        val platform = LspSemanticTokensSupport()
        assertTrue(semantic.tokenTypes.containsAll(platform.tokenTypes))
        assertTrue(semantic.tokenTypes.containsAll(semanticTokenTypes))
        assertEquals(semantic.tokenTypes.size, semantic.tokenTypes.toSet().size)

        val keys = semanticTokenTypes.map { tokenType ->
            val key = semantic.getTextAttributesKey(tokenType, emptyList())
            assertNotNull(tokenType, key)
            assertTrue(tokenType, checkNotNull(key).externalName.startsWith("LEAN_"))
            key
        }
        assertEquals(semanticTokenTypes.size, keys.toSet().size)
    }

    fun testModifiersSelectTheirStyleWithoutLosingTheBaseTokenMapping() {
        val semantic = LeanSemanticTokensSupport()
        assertTrue(semantic.tokenModifiers.containsAll(LspSemanticTokensSupport().tokenModifiers))
        assertTrue("markupSyntax" in semantic.tokenModifiers)
        assertSame(
            LeanColor.DOC_COMMENT.key,
            semantic.getTextAttributesKey(SemanticTokenTypes.Comment, listOf(SemanticTokenModifiers.Documentation))
        )
        assertSame(
            LeanColor.MARKUP_SYNTAX.key,
            semantic.getTextAttributesKey("keyword", listOf("documentation", "markupSyntax"))
        )
        for ((tokenType, color) in listOf(
            SemanticTokenTypes.Method to LeanColor.STATIC_METHOD,
            SemanticTokenTypes.Property to LeanColor.STATIC_PROPERTY,
            SemanticTokenTypes.EnumMember to LeanColor.STATIC_ENUM_MEMBER
        )) {
            assertSame(
                tokenType, color.key,
                semantic.getTextAttributesKey(tokenType, listOf(SemanticTokenModifiers.Static))
            )
        }
        for (tokenType in semanticTokenTypes) {
            assertSame(
                tokenType,
                semantic.getTextAttributesKey(tokenType, emptyList()),
                semantic.getTextAttributesKey(tokenType, listOf("projectDefinedModifier"))
            )
        }
        for (modifiers in listOf(emptyList(), listOf("documentation", "static"))) {
            assertNull(semantic.getTextAttributesKey("projectDefinedToken", modifiers))
        }
    }

    fun testLeanColorsFollowTheThemeWithoutChangingOtherLanguages() {
        val scheme = EditorColorsManager.getInstance().globalScheme.clone() as EditorColorsScheme
        val inherited = scheme.getAttributes(DefaultLanguageHighlighterColors.STRING).clone().apply {
            foregroundColor = Color(30, 80, 130)
        }
        scheme.setAttributes(DefaultLanguageHighlighterColors.STRING, inherited)
        assertEquals(inherited, scheme.getAttributes(LeanColor.STRING.key))

        val customized = inherited.clone().apply { foregroundColor = Color(180, 40, 80) }
        scheme.setAttributes(LeanColor.STRING.key, customized)
        assertEquals(customized, scheme.getAttributes(LeanColor.STRING.key))
        assertEquals(inherited, scheme.getAttributes(DefaultLanguageHighlighterColors.STRING))
    }

    fun testSemanticTypesCanBeCustomizedIndependentlyWhileInheritingTheTheme() {
        val semantic = LeanSemanticTokensSupport()
        val scheme = EditorColorsManager.getInstance().globalScheme.clone() as EditorColorsScheme
        val defaultKey = DefaultLanguageHighlighterColors.CLASS_NAME
        val inherited = scheme.getAttributes(defaultKey).clone().apply { foregroundColor = Color(30, 80, 130) }
        scheme.setAttributes(defaultKey, inherited)
        val types = listOf(SemanticTokenTypes.Type, SemanticTokenTypes.Class, SemanticTokenTypes.Struct)
        for (type in types) {
            val key = checkNotNull(semantic.getTextAttributesKey(type, emptyList()))
            assertEquals(type, inherited, scheme.getAttributes(key))
        }

        val customized = inherited.clone().apply { foregroundColor = Color(180, 40, 80) }
        val typeKey = checkNotNull(semantic.getTextAttributesKey(SemanticTokenTypes.Type, emptyList()))
        scheme.setAttributes(typeKey, customized)
        assertEquals(customized, scheme.getAttributes(typeKey))
        assertEquals(inherited, scheme.getAttributes(defaultKey))
        assertEquals(inherited, scheme.getAttributes(LeanColor.CLASS.key))
        assertEquals(inherited, scheme.getAttributes(LeanColor.STRUCT.key))
    }

    fun testColorSettingsExposeSemanticAndProcessingStyles() {
        val page = ColorSettingsPage.EP_NAME.extensionList.filterIsInstance<LeanColorSettingsPage>().single()
        val keys = page.attributeDescriptors.map { it.key }.toSet()
        val previewKeys = Regex("<([a-z_]+)>").findAll(page.demoText).map { tag ->
            page.additionalHighlightingTagToDescriptorMap[tag.groupValues[1]]
        }.toSet()
        val semantic = LeanSemanticTokensSupport()
        for (tokenType in semanticTokenTypes) {
            val key = semantic.getTextAttributesKey(tokenType, emptyList())
            assertTrue("Settings for $tokenType", key in keys)
            assertTrue("Preview for $tokenType", key in previewKeys)
        }
        assertTrue(LeanColor.PROCESSING.key in keys)
        assertTrue(LeanColor.PROCESSING_STOPPED_STRIPE.key in keys)
        assertSame(LeanColor.SORRY_LIKE.key, page.additionalHighlightingTagToDescriptorMap["sorry_like"])
    }
}
