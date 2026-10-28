import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    kotlin("jvm") version "2.4.20" apply false
    id("org.jetbrains.intellij.platform") version "2.19.0" apply false
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
}

// release: ./gradlew -PpreloVersion=0.1.0 ...
val preloVersion = providers.gradleProperty("preloVersion").getOrElse("0.1.0-SNAPSHOT")

allprojects {
    group = "io.github.mateusvpassos"
    version = preloVersion
}

val bibliotecas = mapOf(
    "prelo-core" to "Declarative JSON report engine: PDF, XLSX, HTML and CSV",
    "prelo-fonts" to "DejaVu Sans as the default Prelo font (full Unicode)",
    "prelo-jrxml" to "JasperReports .jrxml to Prelo .report.json converter",
    "prelo-spring-boot-starter" to "Spring Boot auto-configuration for Prelo",
    "prelo-quarkus" to "Quarkus CDI producer and JAX-RS helpers for Prelo",
)

subprojects {
    val descricao = bibliotecas[name] ?: return@subprojects
    plugins.withId("java-library") {
        apply(plugin = "com.vanniktech.maven.publish")

        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).apply {
                addStringOption("Xdoclint:none", "-quiet")
                encoding = "UTF-8"
            }
        }

        extensions.configure<MavenPublishBaseExtension> {
            configure(JavaLibrary(JavadocJar.Javadoc(), SourcesJar.Sources()))
            publishToMavenCentral()
            // assina só com chave (CI); publishToMavenLocal segue funcionando sem
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
            coordinates(group.toString(), name, version.toString())
            pom {
                name.set(project.name)
                description.set(descricao)
                inceptionYear.set("2026")
                url.set("https://github.com/mateusvpassos/prelo")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("mateusvpassos")
                        name.set("Mateus Passos")
                        url.set("https://github.com/mateusvpassos")
                    }
                }
                scm {
                    url.set("https://github.com/mateusvpassos/prelo")
                    connection.set("scm:git:https://github.com/mateusvpassos/prelo.git")
                    developerConnection.set("scm:git:ssh://git@github.com/mateusvpassos/prelo.git")
                }
            }
        }
    }
}
