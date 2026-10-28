plugins {
    `java-library`
}

repositories { mavenCentral() }

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    implementation("io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.87")
    implementation("org.dhatim:fastexcel:0.20.2")
    implementation("com.google.zxing:core:3.5.4")
    implementation("org.apache.pdfbox:pdfbox:3.0.7")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.apache.pdfbox:pdfbox:3.0.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}


tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("prelo.perf", System.getProperty("prelo.perf") ?: "false")
    systemProperty("galeria", System.getProperty("galeria") ?: "false")
    maxHeapSize = System.getProperty("prelo.heap") ?: "2g"
    testLogging { showStandardStreams = System.getProperty("prelo.perf") == "true" }
}
