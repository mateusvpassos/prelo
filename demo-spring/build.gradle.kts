plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "demo"
version = "0.0.1"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

repositories {
    mavenLocal()   // prelo publicado com ./gradlew publishToMavenLocal (na raiz do repo)
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("io.github.mateusvpassos:prelo-spring-boot-starter:0.1.0-SNAPSHOT")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.test { useJUnitPlatform() }
