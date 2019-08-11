import de.marcphilipp.gradle.nexus.NexusPublishExtension

plugins {
    id("de.marcphilipp.nexus-publish") apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "de.marcphilipp.nexus-publish")

    group = "com.example"
    version = "0.0.1"

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])
            }
        }
    }

    configure<NexusPublishExtension> {
        repositories {
            sonatype()
        }
    }
}
