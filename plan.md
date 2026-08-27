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
- [x] Enum member completion, narrowed to the literals the corpus assigns to *that key*
      (`S2CfgEnumIndex`), so `ItemType =` offers `EItemType::*` and not every enum in the game
- [x] SID value completion (local structs first, then project-wide)
- [x] `refkey` completion
- [x] Inspection: unresolved `refurl` path (on by default)
- [x] Inspection: duplicate key in struct (on by default)
- [x] Inspection: unresolved record reference (**off** by default — mods reference base-game records
      whose cfgs are usually outside the project, so it would light up everywhere)

## Stage 4 — repo-aware extras
- [x] Navigate | Related Symbol jumps between `Foo_patch_MyMod.cfg` and `Foo.cfg`, in both
      directions, tie-broken by shared `GameData/...` path
- [x] Ctrl+Q documentation for a record: fields, `refkey`/`refurl` inheritance, nested structs
- [~] Gutter action showing s2cfgtojson JSON — dropped. It would mean shelling out to node from the
      IDE for output the docs popup already covers in a form the plugin can render itself.
- [ ] Interactive smoke test in `runIde`

## Verification status
- 22 tests green (`./gradlew test`), including a parse of all 7709 cfgs in `S2Mods/Mods`
- `./gradlew verifyPlugin` -> Compatible with IC-243.21565.193, dynamically loadable
- Everything is verified through test fixtures. No interactive `runIde` session has been run, so
  the live editor feel (folding placeholders, popup rendering, completion ordering) is unconfirmed.
