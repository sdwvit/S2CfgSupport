# S2CfgSupport — IntelliJ plugin for STALKER 2 `.cfg` files

Grammar (derived from `~/IdeaProjects/S2CfgToJSON/Struct.mts`):

```
file        := (comment | struct)*
struct      := [key ':'] 'struct.begin' [ '{' refs '}' ] body 'struct.end'
body        := (comment | struct | entry)*
entry       := key '=' value
key         := IDENT | '[' (INT | '*') ']'
refs        := ref (';' ref)*      ref := ('refurl'|'refkey'|'bskipref'|'bpatch'|'removenode') ['=' text]
value       := bool | number('f'?) | 'EEnum::Member' | text | <empty> ; may carry a trailing '{' refs '}'
comment     := line starting with '#' or '//'
```

## Stage 1 — parser + highlighting + structure (this pass)
- [x] Gradle scaffold: `intellij-platform-gradle-plugin` 2.x, JDK 21, IDEA 2024.3+ target
- [x] `S2CfgLanguage`, `S2CfgFileType` (`.cfg`), icon
- [x] Hand-written `LexerBase` producing tokens: IDENT, INT, FLOAT, BOOL, COLON, EQ, LBRACKET/RBRACKET, LBRACE/RBRACE, SEMI, STRUCT_BEGIN, STRUCT_END, ENUM, TEXT, COMMENT, WS, EOL
- [x] `S2CfgParser` (PsiBuilder) building STRUCT / STRUCT_HEAD / REFS / ENTRY / KEY / VALUE nodes
- [x] PSI classes + `S2CfgFile`, `ParserDefinition`
- [x] `SyntaxHighlighter` + `ColorSettingsPage`
- [x] Block folding on `struct.begin`…`struct.end` (placeholder = struct name)
- [x] Structure view: struct tree with names / `[N]` indices
- [x] Brace matcher (`[]`, `{}`) and `#` commenter
- [x] `./gradlew buildPlugin` green
- [x] Parser test asserts zero error elements across all 7709 cfgs in `S2Mods/Mods`
- [ ] Interactive smoke test in `runIde` (not run yet — needs a desktop session)

## Stage 2 — SID index + navigation
- [x] `FileBasedIndex` of `SID = X` declarations and struct names (`S2CfgSidIndex`, text-based depth scan)
- [x] Declaration vs reference rule: a `SID` is a declaration only at the top level of a file
- [x] SID-valued entries (`SID`, `*SID`) resolve to the declaring struct, cross-file
- [x] `refurl` resolves to the target cfg file; `refkey` to the struct inside it (falling back to
      same file, then project-wide)
- [x] `refkey=[0]` correctly yields no reference
- [x] Find usages + rename for SIDs (rename rewrites struct name, its `SID`, and all references)
- [x] Go to Symbol (Ctrl+Alt+Shift+N) by SID
- [ ] Interactive smoke test in `runIde`

Note: references are attached by the PSI elements themselves. A `psi.referenceContributor` does
nothing here — contributed references are only consulted for `ContributedReferenceHost` elements,
which custom-language PSI is not.

## Stage 3 — completion + inspections
- [ ] Enum member completion (index all `EFoo::Bar` seen in game data)
- [ ] SID value completion
- [ ] Inspections: dangling SID reference, unknown `refurl` path, duplicate key in struct

## Stage 4 — repo-aware extras
- [ ] Jump from `*_patch_*.cfg` to the base game cfg it overrides
- [ ] Gutter action: show s2cfgtojson JSON for the struct under caret
