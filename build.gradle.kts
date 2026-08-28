import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.1.0"
  id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.sdwvit"
version = "0.2.3"

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

dependencies {
  intellijPlatform {
    intellijIdeaCommunity("2024.3")
    pluginVerifier()
    testFramework(TestFrameworkType.Platform)
  }
  testImplementation("junit:junit:4.13.2")
}

tasks.test {
  systemProperty("idea.home.path", "")
}

kotlin { jvmToolchain(21) }

intellijPlatform {
  pluginVerification {
    // `recommended()` asks for IDE builds that are not published for download here.
    //
    // 2024.3 is what the plugin compiles against; 2026.1 is the line it actually runs on, and
    // `untilBuild` is open ended, so the listing claims everything in between. Community has no
    // published archive for the 2026.1 line (`ideaIC-2026.1.1.tar.gz` is a 404), so that end is
    // covered by Ultimate, which is a superset for API verification.
    //
    // Both targets are downloads: `--offline verifyPlugin` only works once they are cached.
    ides {
      ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3")
      ide(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1.1")
    }
  }

  pluginConfiguration {
    ideaVersion {
      sinceBuild = "243"
      untilBuild = provider { null }
    }

    changeNotes = """
      <h4>0.2.3</h4>
      <ul>
        <li>The key name now picks the target. Record names are not unique across kinds — every
        prototype file may declare its own <code>Default</code> — so <code>LastPhraseSID</code>
        resolves into <code>DialogPrototypes/</code> and <code>UpgradeSID</code> into
        <code>UpgradePrototypes.cfg</code> instead of offering every same-named record. Candidates
        outside the key's location are dropped before they are parsed, which also makes the resolve
        cheaper. The 139 key-to-location pairs are measured from the shipped GameData, and a
        project laid out differently resolves as before.</li>
        <li>Keys that hold a record name without saying so — <code>BlockingBodyMeshes</code>,
        <code>AvailableDialogs</code>, <code>DialogMembers</code>, <code>Faction</code>,
        <code>TargetNode</code>, <code>PreinstalledUpgrades</code> and some forty more — now
        navigate, complete and rename like <code>*SID</code> keys do. The list is derived from the
        shipped GameData: a key qualifies when at least 90% of its values name a declared record
        and the schema types it as a string, which keeps <code>DLC = BaseGame</code> and
        <code>PresetName = Default</code> out. Roughly fifty thousand corpus entries that were
        inert text before.</li>
        <li><code>Guid</code>-suffixed keys (<code>PlaceholderActorGuid</code>,
        <code>TriggerQuestGuid</code>) are recognised as references.</li>
        <li>Every name in a list-valued reference key is clickable, not just the first: a value like
        <code>RequiredUpgradeIDs = Up_01, Up_02</code> now carries one reference per name, and the
        unresolved-record inspection reports each of them separately.</li>
        <li><code>Sid</code>-cased and <code>SIDS</code>-cased key spellings are recognised
        alongside <code>SID</code>, <code>Id</code> and <code>IDs</code>.</li>
        <li>Values that cannot be record names — numbers and <code>EItemType::Armor</code>-style
        enum literals — no longer produce a reference under a <code>*SID</code> key.</li>
      </ul>

      <h4>0.2.0</h4>
      <p><b>Fixes IDE freezes.</b> A <code>;</code> comment outside <code>{...}</code> — as in
      <code>PhysicsInteractionPrototypes.cfg</code> — used to hang the IDE outright, and every
      keystroke re-tokenized the whole file, which froze the editor on the multi-megabyte cfgs in
      GameData. Lexing is now streaming and <code>;</code> is a comment, like <code>#</code> and
      <code>//</code>.</p>
      <ul>
        <li>Array elements navigate: <code>FittingWeaponsSIDs</code> / <code>[0] = GunPM_HG</code>
        resolves, and the plural key spellings (<code>UpgradePrototypeSIDs</code>,
        <code>RequiredUpgradeIDs</code>) are recognised — some six thousand corpus entries that
        were inert text before.</li>
        <li>Values such as <code>1.0</code> are highlighted as numbers rather than as text.</li>
        <li>Completion and reference resolution are bounded and cached, so neither stalls on a
        project holding hundreds of thousands of records.</li>
        <li>Slow resolves and completions are logged to idea.log, since a freeze otherwise leaves
        nothing to go on.</li>
      </ul>

      <h4>0.1.0</h4>
      <p>First release: syntax highlighting, folding, structure view, brace matching and commenting
      for STALKER 2 <code>.cfg</code> game data, plus navigation across records — <code>SID</code>
      references, <code>refkey</code> inheritance and <code>refurl</code> paths all resolve, with
      find usages, rename, completion and inspections.</p>
    """.trimIndent()
  }

  // Marketplace accepts automated uploads only for a plugin whose first version was already
  // approved. Generate a Permanent Token at https://plugins.jetbrains.com under My Tokens
  // (an account password will not work) and run: PUBLISH_TOKEN=... ./gradlew publishPlugin
  publishing {
    token = providers.environmentVariable("PUBLISH_TOKEN")
    // `-PpublishChannel=eap` puts the build on the EAP channel instead of the default one
    channels = providers.gradleProperty("publishChannel").map { listOf(it) }.orElse(listOf("default"))
  }
}
