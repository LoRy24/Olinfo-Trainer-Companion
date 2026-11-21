plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

group = "io.github.olinfolabs"
version = "0.4.2"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

val ideVersion = providers.gradleProperty("ideVersion").orElse("2024.3.6")
val ideType = providers.gradleProperty("ideType").orElse("IC")

dependencies {
    implementation("org.jsoup:jsoup:1.21.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("junit:junit:4.13.2")

    intellijPlatform {
        create(ideType.get(), ideVersion.get())
        pluginVerifier()
    }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

intellijPlatform {
    instrumentCode = false
    buildSearchableOptions = false
    projectName = "olinfo-trainer-companion"
    pluginConfiguration {
        name = "Olinfo Trainer Companion"
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            create("CL", "2024.3.6")
            create("PY", "2024.3.6")
            create("IC", "2024.3.6")
        }
    }
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.processResources {
    from(rootProject.file("shared/catalog.properties"))
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(rootProject.file("assets/oii")) { into("META-INF/oii") }
}
tasks.processTestResources { from(rootProject.file("shared/fixtures")) }
tasks.test {
    useJUnitPlatform()
    val live = providers.environmentVariable("OLINFO_LIVE_TEST").orElse("0")
    inputs.property("olinfoLiveTest", live)
    outputs.upToDateWhen { live.get() != "1" }
}
