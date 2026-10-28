plugins {
    java
    id("io.quarkus") version "3.40.1"
}

repositories {
    mavenLocal()   // prelo publicado com ./gradlew publishToMavenLocal (na raiz do repo)
    mavenCentral()
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.40.1"))
    implementation("io.quarkus:quarkus-rest")
    implementation("io.github.mateusvpassos:prelo-quarkus:0.1.0-SNAPSHOT")
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}
