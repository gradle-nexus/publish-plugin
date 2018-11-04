pluginManagement {
    repositories {
        mavenCentral()
        maven(url = uri("https://jitpack.io"))
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "de.marcphilipp.nexus-publish") {
                useModule("com.github.marcphilipp.nexus-publish-plugin:de.marcphilipp.nexus-publish.gradle.plugin:a25220b403")
            }
        }
    }
}

rootProject.name = "multi-project-sample"

include("a", "b")
