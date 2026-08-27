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
    // `recommended()` asks for IDE builds that are not published for download here
    ides { ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3") }
  }

  pluginConfiguration {
    ideaVersion {
      sinceBuild = "243"
      untilBuild = provider { null }
    }
  }
}
