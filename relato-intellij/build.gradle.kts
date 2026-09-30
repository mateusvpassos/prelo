import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation(project(":relato-core"))
    implementation("org.apache.pdfbox:pdfbox:3.0.7")
    intellijPlatform {
        local(providers.gradleProperty("ideaLocalPath"))
        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("com.intellij.modules.jcef")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    testImplementation(kotlin("stdlib"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        id = "io.github.mateusvpassos.relato"
        name = "Relato Reports"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
}

// A plataforma 2026.x puxa o target para 25; fixa 21 para o plugin rodar também em IDEs 2024.3+.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

tasks.test {
    // IDE local é Ultimate: carregar só o necessário evita extensões de licença que quebram em teste
    systemProperty("idea.load.plugins.id", "io.github.mateusvpassos.relato,com.intellij.modules.json,com.intellij.modules.jcef,com.intellij.java")
}

tasks.buildPlugin {
    archiveBaseName.set("relato-intellij")
}
