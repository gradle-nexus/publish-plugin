@file:Suppress("UnstableApiUsage") // Property assignment is incubating in Gradle 8.1/8.2.

import org.gradle.initialization.IGradlePropertiesLoader.ENV_PROJECT_PROPERTIES_PREFIX
import org.gradle.initialization.IGradlePropertiesLoader.SYSTEM_PROJECT_PROPERTIES_PREFIX
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "1.2.1"
    id("com.diffplug.spotless") version "6.22.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("org.jetbrains.gradle.plugin.idea-ext")
    id("com.github.ben-manes.versions") version "0.48.0"
    id("org.ajoberstar.stutter") version "0.7.2"
}

base { archivesName = "publish-plugin" }
group = "io.github.gradle-nexus"
version = "2.0.0-SNAPSHOT"

val readableName = "Nexus Publish Plugin"
description = "Gradle Plugin for publishing to Nexus that automates creating, closing, and releasing staging repositories"
val repoUrl = "https://github.com/gradle-nexus/publish-plugin"

@Suppress("UnstableApiUsage") // Using this DSL is the only way on Gradle 8.0, could still change slightly in the future.
gradlePlugin {
    website = repoUrl
    vcsUrl = repoUrl
    plugins {
        create("nexusPublish") {
            id = "io.github.gradle-nexus.publish-plugin"
            displayName = readableName
            implementationClass = "io.github.gradlenexus.publishplugin.NexusPublishPlugin"
            description = project.description
            tags.addAll("publishing", "maven", "nexus")
        }
    }
}

repositories {
    mavenCentral()
}

val licenseHeaderFile = file("gradle/license-header.txt")
spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    kotlin {
        targetExclude("**/*.gradle.kts", "**/build/generated-sources/**/*.kt")
        ktlint().editorConfigOverride(
            mapOf(
                // Trailing comma language feature requires Kotlin plugin 1.4+, at the moment the compilation is done with Kotlin 1.3.
                // This helps spotlessKotlinCheck and spotlessApply to format the code properly, see also .editorconfig.
                "ktlint_standard_trailing-comma-on-call-site" to "disabled",
                "ktlint_standard_trailing-comma-on-declaration-site" to "disabled"
            )
        )
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

configurations {
    testImplementation {
        exclude(group = "junit", module = "junit")
    }
}

dependencies {
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("net.jodah:failsafe:2.4.4")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.github.tomakehurst:wiremock:2.27.2")
    testImplementation("ru.lanwen.wiremock:wiremock-junit5:1.3.1")
    testImplementation("org.assertj:assertj-core:3.24.2")
    // This cannot be updated to 5.x as it requires Java 11,
    // but we are running CI on Java 8 in .github/workflows/java-versions.yml.
    testImplementation("org.mockito:mockito-junit-jupiter:4.11.0")
    testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

stutter {
    sparse = providers.gradleProperty("stutter.sparse").map(String::toBoolean).orElse(true)
    matrices {
        register("java8") {
            javaToolchain {
                languageVersion = JavaLanguageVersion.of(8)
            }
            gradleVersions {
                compatibleRange("6.0")
            }
        }
        register("java11") {
            javaToolchain {
                languageVersion = JavaLanguageVersion.of(11)
            }
            gradleVersions {
                compatibleRange("6.0")
            }
        }
        register("java17") {
            javaToolchain {
                languageVersion = JavaLanguageVersion.of(17)
            }
            gradleVersions {
                compatibleRange("7.3")
            }
        }
    }
}

// Separate infrastructure as compatTest is called multiple times with different Java versions.
val e2eTest: SourceSet by sourceSets.creating {
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

kotlin.target.compilations.configureEach {
    // Supporting Gradle 6.0+ needs to use Kotlin 1.3.
    // See https://docs.gradle.org/current/userguide/compatibility.html
    // For future maintainer: Kotlin 1.9.0 dropped support for Kotlin 1.3, it'll only support 1.4+.
    // This means Gradle 7.0 will be the lowest supportable version for plugins.
    val usedKotlinVersion = @Suppress("DEPRECATION") KotlinVersion.KOTLIN_1_3

    compilerOptions.configure {
        // Gradle fully supports running on Java 8: https://docs.gradle.org/current/userguide/compatibility.html,
        // so we should allow users to do that too.
        jvmTarget = JvmTarget.fromTarget(JavaVersion.VERSION_1_8.toString())

        // Suppress "Language version 1.3 is deprecated and its support will be removed in a future version of Kotlin".
        freeCompilerArgs.add("-Xsuppress-version-warnings")
    }
    compileTaskProvider.configure {
        // These two (api & lang) needs to be here instead of in compilations.compilerOptions.configure { },
        // to prevent KotlinDslCompilerPlugins overriding to Kotlin 1.8.
        compilerOptions.apiVersion = usedKotlinVersion
        // Theoretically we could use newer language version here,
        // but sadly the @kotlin.Metadata created on the classes would be incompatible with older consumers.
        compilerOptions.languageVersion = usedKotlinVersion

        // Validate that we're using the right version.
        doFirst {
            val api = compilerOptions.apiVersion.get()
            val language = compilerOptions.languageVersion.get()
            if (api != usedKotlinVersion || language != usedKotlinVersion) {
                TODO(
                    "There's mismatch between configured and actual versions:\n" +
                            "apiVersion=${api}, languageVersion=${language}, configured=${usedKotlinVersion}."
                )
            }
        }
    }
}

tasks {
    shadowJar {
        exclude("META-INF/maven/**", "META-INF/proguard/**", "META-INF/*.kotlin_module")
        manifest {
            attributes["Implementation-Version"] = project.version
        }
        archiveClassifier = ""
        isEnableRelocation = true
        relocationPrefix = "io.github.gradlenexus.publishplugin.shadow"
    }
    jar {
        enabled = false
        dependsOn(shadowJar)
    }
    pluginUnderTestMetadata {
        pluginClasspath.setFrom(shadowJar)
    }
    register<Test>("e2eTest") {
        description = "Run E2E tests."
        group = "Verification"
        testClassesDirs = e2eTest.output.classesDirs
        classpath = e2eTest.runtimeClasspath
        // Pass E2E releasing properties to tests.
        // Prefer environment variables which are not displayed with --info
        // (unless non CI friendly properties with "." are used).
        listOf(
            "sonatypeUsername",
            "sonatypePassword",
            "signingKey",
            "signingPassword",
            "signing.gnupg.homeDir",
            "signing.gnupg.keyName",
            "signing.gnupg.passphrase"
        ).forEach {
            val e2eName = "${it}E2E"
            val e2eEnvName = "${ENV_PROJECT_PROPERTIES_PREFIX}${e2eName}"
            // Properties defined using ORG_GRADLE_PROJECT_ are accessible in child process anyway.
            if (project.hasProperty(e2eName) && System.getenv(e2eEnvName) == null) {
                val e2eValue = project.property(e2eName)!!
                if (e2eName.contains(".")) {
                    systemProperty("${SYSTEM_PROJECT_PROPERTIES_PREFIX}${e2eName}", e2eValue)
                } else {
                    environment("${ENV_PROJECT_PROPERTIES_PREFIX}${e2eName}", e2eValue)
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
        maxParallelForks = if (name.startsWith("compatTest")) 1 else 8
    }
    withType<Test>().matching { it.name.startsWith("compatTest") }.configureEach {
        systemProperty("plugin.version", project.version)
    }
}

publishing {
    publications {
        afterEvaluate {
            named<MavenPublication>("pluginMaven") {
                artifactId = base.archivesName.get()
                pom {
                    name = readableName
                    description = project.description
                    inceptionYear = "2020"
                    url = repoUrl
                    developers {
                        developer {
                            name = "Marc Philipp"
                            id = "marcphilipp"
                        }
                        developer {
                            name = "Marcin Zajączkowski"
                            id = "szpak"
                        }
                    }
                    licenses {
                        license {
                            name = "Apache License, Version 2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        }
                    }
                }
            }
        }
    }
}
