package plugin.lean.settings

import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import plugin.lean.language.LeanColor
import plugin.lean.language.LeanIcons
import plugin.lean.language.LeanSyntaxHighlighterFactory

class LeanColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName() = "Lean4"
    override fun getIcon() = LeanIcons.LEAN
    override fun getHighlighter() = LeanSyntaxHighlighterFactory().getSyntaxHighlighter(null, null)
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAttributeDescriptors() = LeanColor.entries.map { AttributesDescriptor(it.displayName, it.key) }
        .toTypedArray()

    override fun getAdditionalHighlightingTagToDescriptorMap() = LeanColor.entries.associate {
        it.name.lowercase() to it.key
    }

    override fun getDemoText() = """
        <keyword>namespace</keyword> <namespace>Geometry</namespace>

        /--
        <markup_syntax>#</markup_syntax> <markup_heading>Points and coordinates</markup_heading>

        <markup_text>A point stores two coordinates.</markup_text>
        <markup_syntax>-</markup_syntax> <markup_list>Coordinates are natural numbers.</markup_list>
        <markup_syntax>></markup_syntax> <markup_quote>The origin has zero coordinates.</markup_quote>

        Use <markup_inline_code>`Point`</markup_inline_code> for a pair of coordinates.
        <markup_syntax>```lean</markup_syntax>
        <markup_code_block>#check Point</markup_code_block>
        <markup_syntax>```</markup_syntax>
        See [<markup_cross_reference>reference</markup_cross_reference>]
        (<markup_url>https://lean-lang.org</markup_url>).
        -/
        <keyword>structure</keyword> <struct>Point</struct> <keyword>where</keyword>
          <property>x</property> : <type>Nat</type>
          <property>y</property> : <type>Nat</type>

        <keyword>class</keyword> <interface>HasOrigin</interface>
            (<type_parameter>α</type_parameter> : <class>Type</class>) <keyword>where</keyword>
          <property>origin</property> : <type_parameter>α</type_parameter>

        <keyword>inductive</keyword> <enum>Accepted</enum> : <class>Prop</class> <keyword>where</keyword>
          | <enum_member>intro</enum_member> : <enum>Accepted</enum>

        <modifier>private</modifier> <keyword>def</keyword> <function>message</function> : <type>String</type> :=
          <string>"Hello, Lean"</string>

        <keyword>def</keyword> <function>next</function> (<parameter>n</parameter> : <type>Nat</type>) :=
          <variable>n</variable> <operator>+</operator> <number>1</number>

        /- A proof with a named variable. -/
        <keyword>theorem</keyword> <function>identity</function> (<parameter>n</parameter> : <type>Nat</type>) :
            <variable>n</variable> = <variable>n</variable> := <keyword>by</keyword>
          <keyword>rfl</keyword>
        <keyword>attribute</keyword> [<decorator>simp</decorator>] <function>identity</function>

        <line_comment>-- An unfinished proof</line_comment>
        <keyword>example</keyword> : <enum>False</enum> := <keyword>by</keyword>
          <sorry_like>sorry</sorry_like>

        -- Additional semantic categories for server extensions:
        -- <method>method</method> <static_method>static method</static_method> <macro>macro</macro>
        -- <static_property>static field</static_property> <static_enum_member>static enum member</static_enum_member>
        -- <event>event</event> <regexp>regular expression</regexp>

        <keyword>end</keyword> <namespace>Geometry</namespace>
    """.trimIndent()
}
