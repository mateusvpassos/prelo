plugins {
    `java-library`
    `maven-publish`
    application
}

repositories { mavenCentral() }

dependencies {
    api(project(":relato-core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.apache.pdfbox:pdfbox:3.0.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

application {
    mainClass.set("io.github.mateusvpassos.relato.jrxml.JrxmlCli")
}

tasks.test {
    useJUnitPlatform()
    // pasta com .jrxml reais para o teste de migração em lote (opcional)
    systemProperty("relato.jrxml.corpus", System.getProperty("relato.jrxml.corpus") ?: "")
    systemProperty("relato.jrxml.png", System.getProperty("relato.jrxml.png") ?: "")
    maxHeapSize = "1g"
}

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}
