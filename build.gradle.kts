import com.github.jengelman.gradle.plugins.shadow.tasks.ConfigureShadowRelocation
import org.gradle.initialization.IGradlePropertiesLoader.ENV_PROJECT_PROPERTIES_PREFIX
import org.gradle.initialization.IGradlePropertiesLoader.SYSTEM_PROJECT_PROPERTIES_PREFIX
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "1.1.0"
    id("com.diffplug.spotless") version "6.0.0"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("org.jetbrains.gradle.plugin.idea-ext")
    id("com.github.ben-manes.versions") version "0.45.0"
    id("org.ajoberstar.stutter") version "0.6.0"
}

group = "io.github.gradle-nexus"
version = "1.1.1-SNAPSHOT"

val readableName = "Nexus Publish Plugin"
description = "Gradle Plugin for publishing to Nexus that automates creating, closing, and releasing staging repositories"
val repoUrl = "https://github.com/gradle-nexus/publish-plugin"

@Suppress("UnstableApiUsage") // Using this DSL is the only way on Gradle 8.0, could still change slightly in the future.
gradlePlugin {
    website.set(repoUrl)
    vcsUrl.set(repoUrl)
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
    kotlin {
        targetExclude("**/*.gradle.kts", "**/build/generated-sources/**/*.kt")
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

configurations {
    testImplementation {
        exclude(group = "junit", module = "junit")
    }
    // Workaround https://github.com/gradle/gradle/issues/23928
    shadow.configure { afterEvaluate { this@configure.dependencies.remove(project.dependencies.gradleApi()) } }
}

dependencies {
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("net.jodah:failsafe:2.4.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.8.1")
    testImplementation("com.github.tomakehurst:wiremock:2.27.2")
    testImplementation("ru.lanwen.wiremock:wiremock-junit5:1.3.1")
    testImplementation("org.assertj:assertj-core:3.21.0")
    testImplementation("org.mockito:mockito-junit-jupiter:4.0.0")
    testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

stutter {
    isSparse = (findProperty("stutter.sparce")?.toString()?.toBoolean()) ?: true
    java(8) {
        compatibleRange("5.0")
    }
    java(11) {
        compatibleRange("5.0")
    }
    java(17) {
        compatibleRange("7.3")
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

tasks {
    afterEvaluate {
        // This needs to be in an afterEvaluate block,
        // because otherwise KotlinDslCompilerPlugins would win, and override what we've set to Kotlin 1.8.
        withType<KotlinCompile>().configureEach {
            kotlinOptions.jvmTarget = JavaVersion.VERSION_1_8.toString()
            // Supporting Gradle 5.0+ needs to use Kotlin 1.3.
            // See https://docs.gradle.org/current/userguide/compatibility.html
            kotlinOptions.apiVersion = "1.3"
            // Theoretically we could use newer language version here,
            // but sadly the @kotlin.Metadata created on the classes would be incompatible with Kotlin 1.3 consumers.
            kotlinOptions.languageVersion = "1.3"
            doFirst {
                if (kotlinOptions.apiVersion == "1.3") {
                    // Suppress "Language version 1.3 is deprecated and its support will be removed in a future version of Kotlin".
                    kotlinOptions.freeCompilerArgs += "-Xsuppress-version-warnings"
                } else {
                    TODO("Remove -Xsuppress-version-warnings suppression, or change the condition to ${kotlinOptions.languageVersion}")
                }
            }
        }
    }
    val relocateShadowJar by registering(ConfigureShadowRelocation::class) {
        target = shadowJar.get()
        prefix = "io.github.gradlenexus.publishplugin.shadow"
    }
    shadowJar {
        dependsOn(relocateShadowJar)
        exclude("META-INF/maven/**", "META-INF/proguard/**", "META-INF/*.kotlin_module")
        manifest {
            attributes["Implementation-Version"] = project.version
        }
        archiveClassifier.set("")
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
}

publishing {
    publications {
        afterEvaluate {
            named<MavenPublication>("pluginMaven") {
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

