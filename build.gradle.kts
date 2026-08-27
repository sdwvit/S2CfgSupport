import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.1.0"
  id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.sdwvit"
version = "0.1.0"

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
