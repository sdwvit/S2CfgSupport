# STALKER 2 Cfg Support

An IntelliJ plugin that understands the `.cfg` files STALKER 2 uses for game data.

**[Get it on the JetBrains Marketplace](https://plugins.jetbrains.com/plugin/33872-stalker-2-cfg-support?noRedirect=true)**
&nbsp;
[![Version](https://img.shields.io/jetbrains/plugin/v/33872.svg)](https://plugins.jetbrains.com/plugin/33872-stalker-2-cfg-support?noRedirect=true)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/33872.svg)](https://plugins.jetbrains.com/plugin/33872-stalker-2-cfg-support?noRedirect=true)

Install it from the IDE with Settings | Plugins | Marketplace, and search for "STALKER 2 Cfg".

## What it does

**Reading**
- Syntax highlighting, with a configurable color scheme (Settings | Editor | Color Scheme | STALKER 2 Cfg)
- Folding for every `struct.begin`…`struct.end`, with a placeholder showing field and child counts
- Structure view of the struct tree, annotated with `→ ../Base/Armor.cfg:Battle_Varta_Armor`
  inheritance hints
- Ctrl+Q on a record shows its fields, what it inherits, and its nested structs

**Navigating**
- Ctrl-click a `SID` reference to jump to the record it names, across files
- `refkey` resolves to the base struct (in the `refurl` file, else the same file, else project-wide);
  `refurl` opens the target cfg
- Find Usages and Rename for records — renaming rewrites the struct name, its `SID`, and every reference
- Go to Symbol (Ctrl+Alt+Shift+N) by SID
- Navigate | Related Symbol moves between `Foo_patch_MyMod.cfg` and the `Foo.cfg` it patches

**Localization assets**
- A Mod SDK `*-localization.uasset` package opens as the JSON its `LocalizedTexts` export
  describes, and is written back into the binary package on save — no SDK round trip to change a
  line of dialogue
- Recognised by the package header, not the file name
- Copy JSON / Paste JSON in the editor toolbar, in the same shape the S2Mods
  `localization-uasset.mts` dump prints, so a document moves between the two tools
- A document that is not valid JSON, or not a localization document, is not saved: the banner under
  the editor says why. A language the package's name table does not hold is refused too, since the
  writer cannot add one

**Writing**
- Completion for enum literals, narrowed to the ones the corpus actually assigns to that key
- Completion for `SID` values and `refkey`
- Inspections: unresolved `refurl` path, duplicate key in a struct, and (off by default) unresolved
  record reference

## The grammar

Derived from [s2cfgtojson](https://github.com/sdwvit/s2cfgtojson)'s `Struct.mts`, which is the
authority on how the game reads these files:

```
file    := (comment | struct)*
struct  := [key ':'] 'struct.begin' [ '{' refs '}' ] body 'struct.end'
         | key ':' 'removenode'
body    := (comment | struct | entry)*
entry   := key '=' value [ '{' refs '}' ]
key     := IDENT | '[' (INT | '*') ']'
refs    := ref (';' ref)*
ref     := ('refurl'|'refkey'|'bskipref'|'bpatch'|'removenode') ['=' text]
value   := bool | number('f'?) | 'EEnum::Member' | text | <empty>
comment := a line starting with '#' or '//'
```

Two things the corpus taught that the docs don't say: `Key : removenode` is a bodyless struct that
deletes an inherited one, and some `SpawnActorPrototypes` files start with a UTF-8 BOM.

The rule that makes navigation useful: `SID = X` *directly inside a top-level struct* declares that
record, while the same line nested deeper (inside `Launchers` → `Connections`) is a **reference** to
another record. Only the former is indexed.

## Building

```bash
JAVA_HOME=~/.jdks/jbr-17.0.14 ./gradlew buildPlugin   # -> build/distributions/S2CfgSupport-0.3.0.zip
JAVA_HOME=~/.jdks/jbr-17.0.14 ./gradlew test          # 50 tests, incl. a parse of the whole S2Mods corpus
JAVA_HOME=~/.jdks/jbr-17.0.14 ./gradlew runIde        # sandbox IDE
```

Gradle fetches its own JDK 21 toolchain (via the foojay resolver), because the JVMs installed
system-wide here are JREs with no `javac`.

Install a locally built zip with Settings | Plugins | ⚙ | Install Plugin from Disk — or take the
released build from the [Marketplace listing](https://plugins.jetbrains.com/plugin/33872-stalker-2-cfg-support?noRedirect=true).

Publishing an update, once a Marketplace [permanent token](https://plugins.jetbrains.com/author/me/tokens)
is in hand:

```bash
PUBLISH_TOKEN=... ./gradlew publishPlugin
```
