plugins {
    java
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":prelo-core"))
    implementation(project(":prelo-fonts"))
    implementation("org.apache.pdfbox:pdfbox:3.0.7")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.apache.pdfbox:pdfbox:3.0.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

application {
    mainClass.set("io.github.mateusvpassos.prelo.server.PreloCli")
    applicationName = "prelo"
}

tasks.test {
    useJUnitPlatform()
}

/** Jar único executável: java -jar prelo-server-all.jar serve */
tasks.register<Jar>("fatJar") {
    group = "build"
    archiveClassifier.set("all")
    manifest { attributes["Main-Class"] = application.mainClass.get() }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
    }
}
