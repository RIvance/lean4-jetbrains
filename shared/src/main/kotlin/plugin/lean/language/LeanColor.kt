package plugin.lean.language

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Defaults
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.tree.IElementType

/** Lean styles inherit the active scheme's defaults until the user customizes this language. */
enum class LeanColor(
    val displayName: String,
    fallback: TextAttributesKey,
    val semanticTokenType: String? = null,
    private val lexicalTokens: List<IElementType> = emptyList(),
    val semanticTokenModifiers: Set<String> = emptySet()
) {
    KEYWORD("Keywords", Defaults.KEYWORD, "keyword"),
    NAMESPACE("Namespaces", Defaults.IDENTIFIER, "namespace"),
    TYPE("Types//Type names", Defaults.CLASS_NAME, "type"),
    CLASS("Types//Classes", Defaults.CLASS_NAME, "class"),
    ENUM("Types//Enums", Defaults.CLASS_NAME, "enum"),
    INTERFACE("Types//Interfaces", Defaults.INTERFACE_NAME, "interface"),
    STRUCT("Types//Structures", Defaults.CLASS_NAME, "struct"),
    TYPE_PARAMETER("Types//Type parameters", Defaults.PARAMETER, "typeParameter"),
    FUNCTION("Functions", Defaults.FUNCTION_DECLARATION, "function"),
    METHOD("Methods//Instance", Defaults.INSTANCE_METHOD, "method"),
    STATIC_METHOD(
        "Methods//Static", Defaults.STATIC_METHOD, "method",
        semanticTokenModifiers = setOf("static")
    ),
    MACRO("Macros", Defaults.METADATA, "macro"),
    VARIABLE("Variables", Defaults.LOCAL_VARIABLE, "variable"),
    PARAMETER("Parameters", Defaults.PARAMETER, "parameter"),
    PROPERTY("Fields//Instance", Defaults.INSTANCE_FIELD, "property"),
    STATIC_PROPERTY(
        "Fields//Static", Defaults.STATIC_FIELD, "property",
        semanticTokenModifiers = setOf("static")
    ),
    ENUM_MEMBER("Enum members//Instance", Defaults.CONSTANT, "enumMember"),
    STATIC_ENUM_MEMBER(
        "Enum members//Static", Defaults.STATIC_FIELD, "enumMember",
        semanticTokenModifiers = setOf("static")
    ),
    EVENT("Events", Defaults.INSTANCE_FIELD, "event"),
    MODIFIER("Modifiers", Defaults.KEYWORD, "modifier"),
    DECORATOR("Attributes", Defaults.METADATA, "decorator"),
    SORRY_LIKE("Admitted proofs and stopped checking", Defaults.KEYWORD, "leanSorryLike"),
    IDENTIFIER("Identifiers", Defaults.IDENTIFIER, lexicalTokens = listOf(LeanTokens.IDENTIFIER)),
    NUMBER("Numbers", Defaults.NUMBER, "number", listOf(LeanTokens.NUMBER)),
    STRING("Strings and characters", Defaults.STRING, "string", listOf(LeanTokens.STRING)),
    REGEXP("Regular expressions", Defaults.STRING, "regexp"),
    LINE_COMMENT("Comments//Line comment", Defaults.LINE_COMMENT, "comment", listOf(LeanTokens.LINE_COMMENT)),
    BLOCK_COMMENT("Comments//Block comment", Defaults.BLOCK_COMMENT, lexicalTokens = listOf(LeanTokens.BLOCK_COMMENT)),
    DOC_COMMENT(
        "Comments//Documentation", Defaults.DOC_COMMENT, "comment", listOf(LeanTokens.DOC_COMMENT),
        semanticTokenModifiers = setOf("documentation")
    ),
    MARKUP_TEXT("Documentation markup//Text", Defaults.DOC_COMMENT, "markupText"),
    MARKUP_HEADING("Documentation markup//Headings", Defaults.DOC_COMMENT_TAG, "markupHeading"),
    MARKUP_QUOTE("Documentation markup//Block quotes", Defaults.DOC_COMMENT, "markupQuote"),
    MARKUP_LIST("Documentation markup//Lists", Defaults.DOC_COMMENT, "markupList"),
    MARKUP_INLINE_CODE("Documentation markup//Inline code", Defaults.DOC_CODE_INLINE, "markupInlineCode"),
    MARKUP_CODE_BLOCK("Documentation markup//Code blocks", Defaults.DOC_CODE_BLOCK, "markupCodeBlock"),
    MARKUP_URL("Documentation markup//URLs", CodeInsightColors.HYPERLINK_ATTRIBUTES, "markupUrl"),
    MARKUP_CROSS_REFERENCE(
        "Documentation markup//Cross references", Defaults.DOC_COMMENT_TAG_VALUE, "markupCrossReference"
    ),
    MARKUP_SYNTAX(
        "Documentation markup//Syntax", Defaults.DOC_COMMENT_MARKUP, "keyword",
        semanticTokenModifiers = setOf("markupSyntax")
    ),
    OPERATOR("Symbols and operators", Defaults.OPERATION_SIGN, "operator", listOf(LeanTokens.SYMBOL)),
    PARENTHESES(
        "Braces and brackets//Parentheses", Defaults.PARENTHESES,
        lexicalTokens = listOf(LeanTokens.LEFT_PAREN, LeanTokens.RIGHT_PAREN)
    ),
    BRACKETS(
        "Braces and brackets//Brackets", Defaults.BRACKETS,
        lexicalTokens = listOf(LeanTokens.LEFT_BRACKET, LeanTokens.RIGHT_BRACKET)
    ),
    BRACES(
        "Braces and brackets//Braces", Defaults.BRACES,
        lexicalTokens = listOf(LeanTokens.LEFT_BRACE, LeanTokens.RIGHT_BRACE)
    ),
    PROCESSING("Server status//Processing gutter", HighlighterColors.TEXT),
    PROCESSING_STRIPE("Server status//Processing scrollbar", CodeInsightColors.WARNINGS_ATTRIBUTES),
    PROCESSING_STOPPED("Server status//Stopped gutter", HighlighterColors.TEXT),
    PROCESSING_STOPPED_STRIPE("Server status//Stopped scrollbar", CodeInsightColors.ERRORS_ATTRIBUTES);

    val key: TextAttributesKey = TextAttributesKey.createTextAttributesKey("LEAN_$name", fallback)

    companion object {
        private val lexical = entries.flatMap { color -> color.lexicalTokens.map { it to color } }.toMap()
        private val semantic = entries.filter { it.semanticTokenType != null }.groupBy { it.semanticTokenType }

        fun forToken(token: IElementType): LeanColor? = lexical[token]

        fun forSemanticToken(type: String, modifiers: List<String>): LeanColor? = semantic[type]
            ?.filter { modifiers.containsAll(it.semanticTokenModifiers) }
            ?.maxByOrNull { it.semanticTokenModifiers.size }
    }
}
