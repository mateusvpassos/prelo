plugins {
    `java-library`
    `maven-publish`
}

repositories { mavenCentral() }

dependencies {
    api(project(":prelo-core"))
    compileOnly("jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")
    compileOnly("jakarta.inject:jakarta.inject-api:2.0.1")
    compileOnly("org.eclipse.microprofile.config:microprofile-config-api:3.1")
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}
