pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "prelo"
include("prelo-core", "prelo-fonts", "prelo-jrxml", "prelo-spring-boot-starter", "prelo-quarkus", "prelo-server", "prelo-intellij")
