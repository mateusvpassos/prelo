pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "relato"
include("relato-core", "relato-fonts", "relato-jrxml", "relato-spring-boot-starter", "relato-quarkus", "relato-server", "relato-intellij")
