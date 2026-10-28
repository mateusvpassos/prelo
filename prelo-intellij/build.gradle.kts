import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

version = version.toString().removeSuffix("-SNAPSHOT")

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation(project(":prelo-core"))
    implementation(project(":prelo-jrxml"))
    implementation("org.apache.pdfbox:pdfbox:3.0.7")
    intellijPlatform {
        // IDE local (sem download) se existir; senão baixa ideaVersion (CI)
        val local = providers.gradleProperty("ideaLocalPath").orNull?.takeIf { file(it).exists() }
        if (local != null) local(local) else intellijIdea(providers.gradleProperty("ideaVersion"))
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
        id = "io.github.mateusvpassos.prelo"
        name = "Prelo Reports"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
    publishing {
        token = providers.environmentVariable("JETBRAINS_TOKEN")
    }
    signing {
        certificateChain = providers.environmentVariable("JETBRAINS_CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("JETBRAINS_PRIVATE_KEY")
        password = providers.environmentVariable("JETBRAINS_PRIVATE_KEY_PASSWORD")
    }
}

// A plataforma 2026.x puxa o target para 25; fixa 21 para o plugin rodar também em IDEs 2024.3+.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

tasks.test {
    // IDE local é Ultimate: carregar só o necessário evita extensões de licença que quebram em teste
    systemProperty("idea.load.plugins.id", "io.github.mateusvpassos.prelo,com.intellij.modules.json,com.intellij.modules.jcef,com.intellij.java")
}

tasks.buildPlugin {
    archiveBaseName.set("prelo-intellij")
}
