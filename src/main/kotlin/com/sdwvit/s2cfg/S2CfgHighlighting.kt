package com.sdwvit.s2cfg

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey as key
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import javax.swing.Icon

object S2CfgColors {
  val COMMENT = key("S2CFG_COMMENT", D.LINE_COMMENT)
  val KEYWORD = key("S2CFG_KEYWORD", D.KEYWORD)
  val KEY = key("S2CFG_KEY", D.INSTANCE_FIELD)
  val NUMBER = key("S2CFG_NUMBER", D.NUMBER)
  val BOOLEAN = key("S2CFG_BOOLEAN", D.CONSTANT)
  val ENUM_VALUE = key("S2CFG_ENUM", D.STATIC_FIELD)
  val TEXT = key("S2CFG_TEXT", D.STRING)
  val REF_KEYWORD = key("S2CFG_REF_KEYWORD", D.METADATA)
  val BRACES = key("S2CFG_BRACES", D.BRACES)
  val BRACKETS = key("S2CFG_BRACKETS", D.BRACKETS)
  val OPERATOR = key("S2CFG_OPERATOR", D.OPERATION_SIGN)
}

class S2CfgSyntaxHighlighter : SyntaxHighlighter {
  override fun getHighlightingLexer(): Lexer = S2CfgLexer()

  override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> {
    val k = when (tokenType) {
      S2CfgTypes.COMMENT -> S2CfgColors.COMMENT
      S2CfgTypes.STRUCT_BEGIN, S2CfgTypes.STRUCT_END -> S2CfgColors.KEYWORD
      S2CfgTypes.REMOVE_NODE -> S2CfgColors.REF_KEYWORD
      S2CfgTypes.IDENT -> S2CfgColors.KEY
      S2CfgTypes.NUMBER -> S2CfgColors.NUMBER
      S2CfgTypes.BOOLEAN -> S2CfgColors.BOOLEAN
      S2CfgTypes.ENUM_VALUE -> S2CfgColors.ENUM_VALUE
      S2CfgTypes.TEXT -> S2CfgColors.TEXT
      S2CfgTypes.REF_KEYWORD -> S2CfgColors.REF_KEYWORD
      S2CfgTypes.LBRACE, S2CfgTypes.RBRACE -> S2CfgColors.BRACES
      S2CfgTypes.LBRACKET, S2CfgTypes.RBRACKET, S2CfgTypes.ASTERISK -> S2CfgColors.BRACKETS
      S2CfgTypes.EQ, S2CfgTypes.COLON, S2CfgTypes.SEMICOLON -> S2CfgColors.OPERATOR
      else -> return emptyArray()
    }
    return arrayOf(k)
  }
}

class S2CfgSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
  override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
    S2CfgSyntaxHighlighter()
}

class S2CfgColorSettingsPage : ColorSettingsPage {
  override fun getIcon(): Icon = S2CfgIcons.FILE
  override fun getHighlighter(): SyntaxHighlighter = S2CfgSyntaxHighlighter()
  override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null
  override fun getAttributeDescriptors() = DESCRIPTORS
  override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
  override fun getDisplayName() = "STALKER 2 Cfg"

  override fun getDemoText() = """
    # a game data record
    MyArmor : struct.begin {refkey=Battle_Varta_Armor;bpatch}
       SID = MyArmor
       MaxDurability = 1500.0
       IsQuestItem = false
       ItemType = EItemType::Armor
       Upgrades : struct.begin
          [0] : struct.begin
             UpgradeSID = Upgrade_Armor_01
          struct.end
       struct.end
    struct.end
  """.trimIndent()

  private companion object {
    val DESCRIPTORS = arrayOf(
      AttributesDescriptor("Comment", S2CfgColors.COMMENT),
      AttributesDescriptor("struct.begin / struct.end", S2CfgColors.KEYWORD),
      AttributesDescriptor("Key", S2CfgColors.KEY),
      AttributesDescriptor("Value//Number", S2CfgColors.NUMBER),
      AttributesDescriptor("Value//Boolean", S2CfgColors.BOOLEAN),
      AttributesDescriptor("Value//Enum member", S2CfgColors.ENUM_VALUE),
      AttributesDescriptor("Value//Text", S2CfgColors.TEXT),
      AttributesDescriptor("Modifier keyword (refkey, bpatch, ...)", S2CfgColors.REF_KEYWORD),
      AttributesDescriptor("Braces", S2CfgColors.BRACES),
      AttributesDescriptor("Brackets", S2CfgColors.BRACKETS),
      AttributesDescriptor("Operators", S2CfgColors.OPERATOR),
    )
  }
}
