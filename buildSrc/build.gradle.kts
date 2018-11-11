plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

dependencies {
    compile("gradle.plugin.org.jetbrains.gradle.plugin.idea-ext:gradle-idea-ext:0.4.2")
}
