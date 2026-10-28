plugins {
    `java-library`
}

repositories { mavenCentral() }

dependencies {
    api(project(":prelo-core"))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:3.5.16")
    compileOnly("org.springframework:spring-web:6.2.12")
    testImplementation("org.springframework.boot:spring-boot-autoconfigure:3.5.16")
    testImplementation("org.springframework.boot:spring-boot-test:3.5.16")
    testImplementation("org.springframework:spring-web:6.2.12")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}


tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.test { useJUnitPlatform() }
