plugins {
    `java-library`
}

repositories { mavenCentral() }

dependencies {
    compileOnly(project(":prelo-core"))
    testImplementation(project(":prelo-core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.apache.pdfbox:pdfbox:3.0.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}


tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.test { useJUnitPlatform() }
