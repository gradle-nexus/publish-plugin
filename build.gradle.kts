import org.jetbrains.dokka.gradle.PackageOptions
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
    `maven-publish`
    id("com.gradle.build-scan") version "2.4.2"
    id("com.gradle.plugin-publish") version "0.10.1"
    id("com.diffplug.gradle.spotless") version "3.24.2"
    id("com.github.johnrengelman.shadow") version "5.1.0"
    id("org.jetbrains.gradle.plugin.idea-ext")
    id("com.github.ben-manes.versions") version "0.25.0"
    id("org.jetbrains.dokka") version "0.9.18"
    id("org.ajoberstar.stutter") version "0.5.0"
}

buildScan {
    termsOfServiceUrl = "https://gradle.com/terms-of-service"
    termsOfServiceAgree = "yes"
}

group = "io.github.gradle-nexus"
version = "0.1.0-SNAPSHOT"

val readableName = "Nexus Publish Plugin"
description = "Gradle Plugin for publishing, closing, and releasing to Nexus staging repositories"
val repoUrl = "https://github.com/gradle-nexus/publish-plugin"

pluginBundle {
    description = project.description
    website = repoUrl
    vcsUrl = repoUrl
    tags = listOf("publishing", "maven", "nexus", "travis")
}

gradlePlugin {
    plugins {
        create("nexusPublish") {
            id = "io.github.gradle-nexus.publish-plugin"
            displayName = readableName
            implementationClass = "io.github.gradlenexus.publishplugin.NexusPublishPlugin"
        }
    }
}

repositories {
    mavenCentral()
    jcenter()
}

val licenseHeaderFile = file("gradle/license-header.txt")
spotless {
    kotlin {
        ktlint().userData(mapOf("disabled_rules" to "comment-spacing"))
        licenseHeaderFile(licenseHeaderFile)
    }
}

idea {
    project {
        settings {
            copyright {
                useDefault = "Apache-2.0"
                profiles {
                    create("Apache-2.0") {
                        notice = readCopyrightHeader(licenseHeaderFile)
                        keyword = "Copyright"
                    }
                }
            }
        }
    }
}

val shadowed by configurations.creating
configurations {
    compileOnly {
        extendsFrom(shadowed)
    }
    testImplementation {
        extendsFrom(shadowed)
        exclude(group = "junit", module = "junit")
    }
}

dependencies {
    shadowed("com.squareup.retrofit2:retrofit:2.6.1")
    shadowed("com.squareup.retrofit2:converter-gson:2.6.1")

    val nexusStagingPlugin = create("io.codearte.gradle.nexus:gradle-nexus-staging-plugin:0.21.1")
    compileOnly(nexusStagingPlugin)
    testImplementation(nexusStagingPlugin)

    testImplementation("org.junit.jupiter:junit-jupiter:5.5.2")
    testImplementation("com.github.tomakehurst:wiremock:2.24.1")
    testImplementation("ru.lanwen.wiremock:wiremock-junit5:1.3.1")
    testImplementation("org.assertj:assertj-core:3.13.2")
}

stutter {
    isSparse = (findProperty("stutter.sparce")?.toString()?.toBoolean()) ?: true
    java(8) {
        compatibleRange("4.10")
    }
    java(11) {
        compatibleRange("4.10")
    }
}

configurations {
    compatTestCompileClasspath {
        extendsFrom(testCompileClasspath.get())
    }
    compatTestRuntimeClasspath {
        extendsFrom(testRuntimeClasspath.get())
    }
}

sourceSets {
    compatTest {
        compileClasspath += sourceSets["test"].output
        runtimeClasspath += sourceSets["test"].output
    }
}

tasks {
    withType<KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "1.8"
    }
    shadowJar {
        archiveClassifier.set("")
        configurations = listOf(shadowed)
        exclude("META-INF/maven/**")
        listOf("retrofit2", "okhttp3", "okio", "com").forEach {
            relocate(it, "${project.group}.nexus.shadow.$it")
        }
    }
    jar {
        enabled = false
        dependsOn(shadowJar)
    }
    pluginUnderTestMetadata {
        pluginClasspath.from.clear()
        pluginClasspath.from(shadowJar)
    }
    withType<Test>().configureEach {
        dependsOn(shadowJar)
        useJUnitPlatform()
        maxParallelForks = 8
    }
    dokka {
        outputFormat = "javadoc"
        outputDirectory = "$buildDir/javadoc"
        reportUndocumented = false
        jdkVersion = 8
        packageOptions(delegateClosureOf<PackageOptions> {
            prefix = "io.github.gradlenexus.publishplugin.internal"
            suppress = true
        })
    }
}

val sourcesJar by tasks.creating(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.map { it.allSource })
}

val javadocJar by tasks.creating(Jar::class) {
    archiveClassifier.set("javadoc")
    from(tasks.dokka)
}

// used by plugin-publish plugin
val archives by configurations.getting
archives.artifacts.clear()
artifacts {
    add(archives.name, tasks.shadowJar) {
        classifier = ""
    }
    add(archives.name, sourcesJar)
    add(archives.name, javadocJar)
}

publishing {
    publications {
        afterEvaluate {
            named<MavenPublication>("pluginMaven") {
                artifactId = "publish-plugin"
                artifact(sourcesJar)
                artifact(javadocJar)
                pom {
                    name.set(readableName)
                    description.set(project.description)
                    inceptionYear.set("2019")
                    url.set(repoUrl)
                    developers {
                        developer {
                            name.set("Marc Philipp")
                            id.set("marcphilipp")
                        }
                        developer {
                            name.set("Marcin Zajączkowski")
                            id.set("szpak")
                        }
                    }
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                }
            }
        }
    }
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL
}

