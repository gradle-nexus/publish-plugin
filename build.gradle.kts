import com.github.jengelman.gradle.plugins.shadow.tasks.ConfigureShadowRelocation
import org.gradle.initialization.IGradlePropertiesLoader.ENV_PROJECT_PROPERTIES_PREFIX
import org.gradle.initialization.IGradlePropertiesLoader.SYSTEM_PROJECT_PROPERTIES_PREFIX
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
    `maven-publish`
    id("com.gradle.plugin-publish") version "0.12.0"
    id("com.diffplug.spotless") version "5.8.2"
    id("com.github.johnrengelman.shadow") version "6.1.0"
    id("org.jetbrains.gradle.plugin.idea-ext")
    id("com.github.ben-manes.versions") version "0.36.0"
    id("org.jetbrains.dokka") version "1.4.20"
    id("org.ajoberstar.stutter") version "0.6.0"
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
        targetExclude("**/*.gradle.kts")
        //"import-ordering" required here as it started to fail after spotless plugin upgrade to 0.35.0 - resolve in separate PR
        ktlint().userData(mapOf("disabled_rules" to "comment-spacing,import-ordering"))
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
    shadowed("com.squareup.retrofit2:retrofit:2.9.0")
    shadowed("com.squareup.retrofit2:converter-gson:2.9.0")
    shadowed("net.jodah:failsafe:2.4.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.7.0")
    testImplementation("com.github.tomakehurst:wiremock:2.27.2")
    testImplementation("ru.lanwen.wiremock:wiremock-junit5:1.3.1")
    testImplementation("org.assertj:assertj-core:3.18.1")
    testImplementation("org.mockito:mockito-junit-jupiter:3.6.28")
    testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0")
}

java {
    withJavadocJar()
    withSourcesJar()
}

stutter {
    isSparse = (findProperty("stutter.sparce")?.toString()?.toBoolean()) ?: true
    java(8) {
        compatibleRange("4.10")
    }
    java(11) {
        compatibleRange("5.0")
    }
    java(15) {
        compatibleRange("6.6")
    }
}

val e2eTest by sourceSets.creating {    //separate infrastructure as compatTest is called multiple times with different Java versions
    compileClasspath += sourceSets["compatTest"].output
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["compatTest"].output
    runtimeClasspath += sourceSets["main"].output
}

configurations {
    compatTestCompileClasspath {
        extendsFrom(testCompileClasspath.get())
    }
    compatTestRuntimeClasspath {
        extendsFrom(testRuntimeClasspath.get())
    }
    configurations.named(e2eTest.implementationConfigurationName) {
        extendsFrom(compatTestCompileClasspath.get())
    }
    configurations.named(e2eTest.runtimeOnlyConfigurationName) {
        extendsFrom(compatTestRuntimeClasspath.get())
    }
}

sourceSets {
    compatTest {
        compileClasspath += sourceSets["test"].output
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

kotlinDslPluginOptions {
    experimentalWarning.set(false)
}

tasks {
    withType<KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "1.8"
    }
    val relocateShadowJar by registering(ConfigureShadowRelocation::class) {
        target = shadowJar.get()
        prefix = "${project.group}.nexus.shadow"
    }
    shadowJar {
        dependsOn(relocateShadowJar)
        configurations = listOf(shadowed)
        exclude("META-INF/maven/**", "META-INF/proguard/**", "META-INF/*.kotlin_module")
        manifest {
            attributes["Implementation-Version"] = project.version
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
    register<Test>("e2eTest") {
        description = "Run E2E tests."
        group = "Verification"
        testClassesDirs = e2eTest.output.classesDirs
        classpath = e2eTest.runtimeClasspath
        //pass E2E releasing properties to tests. Prefer environment variables which are not displayed with --info
        // (unless non CI friendly properties with "." are used)
        listOf("sonatypeUsername", "sonatypePassword", "signingKey", "signingPassword", "signing.gnupg.homeDir", "signing.gnupg.keyName", "signing.gnupg.passphrase").forEach {
            val e2eName = "${it}E2E"
            val e2eEnvName = "${ENV_PROJECT_PROPERTIES_PREFIX}${e2eName}"
            //properties defined using ORG_GRADLE_PROJECT_ are accessible in child process anyway
            if (project.hasProperty(e2eName) && System.getenv(e2eEnvName) == null) {
                if (e2eName.contains(".")) {
                    systemProperties.put("${SYSTEM_PROJECT_PROPERTIES_PREFIX}${e2eName}", project.property(e2eName))
                } else {
                    environment("$ENV_PROJECT_PROPERTIES_PREFIX${e2eName}", project.property(e2eName)!!)
                }
            }
        }
        if (project.findProperty("e2eVerboseOutput") != null && project.findProperty("e2eVerboseOutput") != "false") {
            testLogging {
                showStandardStreams = true
            }
        }
    }
    withType<Test>().configureEach {
        dependsOn(shadowJar)
        useJUnitPlatform()
        maxParallelForks = 8
    }
    withType<Test>().matching { it.name.startsWith("compatTest") }.configureEach {
        systemProperty("plugin.version", project.version)
    }
    dokkaJavadoc {
        outputDirectory.set(file("$buildDir/javadoc"))
        dokkaSourceSets.configureEach {
            reportUndocumented.set(false)
            jdkVersion.set(8)
            perPackageOption {
                matchingRegex.set(".*\\.internal($|\\.).*")
                suppress.set(true)
            }
        }
    }
    javadoc {
        enabled = false
    }
    named<Jar>("javadocJar").configure {
        from(dokkaJavadoc)
    }
}

configurations {
    configureEach {
        outgoing {
            val removed = artifacts.removeIf { it.classifier.isNullOrEmpty() }
            if (removed) {
                artifact(tasks.shadowJar) {
                    classifier = ""
                }
            }
        }
    }
    // used by plugin-publish plugin
    archives {
        outgoing {
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))
        }
    }
}

publishing {
    publications {
        afterEvaluate {
            named<MavenPublication>("pluginMaven") {
                artifactId = "publish-plugin"
                pom {
                    name.set(readableName)
                    description.set(project.description)
                    inceptionYear.set("2020")
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

