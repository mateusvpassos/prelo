plugins {
    `java-library`
    `maven-publish`
    application
}

repositories { mavenCentral() }

dependencies {
    api(project(":prelo-core"))
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
    mainClass.set("io.github.mateusvpassos.prelo.jrxml.JrxmlCli")
}

tasks.test {
    useJUnitPlatform()
    // pasta com .jrxml reais para o teste de migração em lote (opcional)
    systemProperty("prelo.jrxml.corpus", System.getProperty("prelo.jrxml.corpus") ?: "")
    systemProperty("prelo.jrxml.png", System.getProperty("prelo.jrxml.png") ?: "")
    maxHeapSize = "1g"
}

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}
