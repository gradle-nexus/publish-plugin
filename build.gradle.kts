import org.gradle.initialization.IGradlePropertiesLoader.ENV_PROJECT_PROPERTIES_PREFIX
import org.gradle.initialization.IGradlePropertiesLoader.SYSTEM_PROJECT_PROPERTIES_PREFIX
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.gradle.ext.copyright
import org.jetbrains.gradle.ext.settings
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import kotlin.math.min
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.8.22"
    id("org.jetbrains.dokka") version "2.2.0"
    id("org.jetbrains.dokka-javadoc") version "2.2.0"
    id("com.gradle.plugin-publish") version "1.3.1"
    id("com.diffplug.spotless") version "7.0.4"
    id("com.gradleup.shadow") version "8.3.6"
    id("org.jetbrains.gradle.plugin.idea-ext") version "1.1.10"
    id("com.github.ben-manes.versions") version "0.51.0"
    id("org.ajoberstar.stutter") version "1.0.0"
}

base { archivesName = "publish-plugin" }
group = "io.github.gradle-nexus"
version = "2.0.1-SNAPSHOT"

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
fun readCopyrightHeader(licenseHeaderFile: File) =
    licenseHeaderFile.readLines()
        .joinToString("\n") { line -> line.substring(min(line.length, 3)) }
        .trim()
spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    kotlin {
        targetExclude("**/*.gradle.kts", "**/build/generated-sources/**/*.kt")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(
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

configurations.testImplementation {
    exclude(group = "junit", module = "junit")
}

dependencies {
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("net.jodah:failsafe:2.4.4")

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.github.tomakehurst:wiremock:2.27.2")
    testImplementation("ru.lanwen.wiremock:wiremock-junit5:1.3.1")
    testImplementation("org.assertj:assertj-core:3.27.6")
    // This cannot be updated to 5.x as it requires Java 11,
    // but we are running CI on Java 8 in .github/workflows/java-versions.yml.
    testImplementation("org.mockito:mockito-junit-jupiter:4.11.0")
    testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0")

    testImplementation(gradleApi())
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
}

stutter {
    sparse = providers.gradleProperty("stutter.sparse").map(String::toBoolean).orElse(true)
    matrices {
        register("java8") {
            javaToolchain {
                languageVersion = JavaLanguageVersion.of(8)
            }
            gradleVersions {
                compatibleRange("6.2")
            }
        }
        register("java11") {
            javaToolchain {
                languageVersion = JavaLanguageVersion.of(11)
            }
            gradleVersions {
                compatibleRange("6.2")
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

// Settings-mode tests share the existing compatibility sources, but run in their own Java 17 task.
val compatibilitySources = sourceSets.named("compatTest")
gradlePlugin.testSourceSet(compatibilitySources.get())
dependencies {
    add(compatibilitySources.get().implementationConfigurationName, gradleTestKit())
    add(compatibilitySources.get().implementationConfigurationName, "org.ow2.asm:asm:9.7.1")
    add(compatibilitySources.get().implementationConfigurationName, "org.bouncycastle:bcpg-jdk18on:1.85")
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

val publicationProofRepository = layout.buildDirectory.dir("publication-proof/repository")

dokka {
    dokkaSourceSets.named("main") {
        sourceRoots.setFrom(file("src/main/kotlin"))
        suppressedFiles.from(fileTree("src/main/kotlin") {
            exclude("io/github/gradlenexus/publishplugin/settings/**", "io/github/gradlenexus/publishplugin/tasks/**")
        }, file("src/main/kotlin/io/github/gradlenexus/publishplugin/settings/NexusSettingsInternals.kt"))
        classpath.from(sourceSets.main.get().compileClasspath)
        jdkVersion.set(17)
        enableKotlinStdLibDocumentationLink.set(false)
        enableJdkDocumentationLink.set(false)
    }
}

tasks {
    register<ValidatePlugins>("validateSettingsPlugins") {
        description = "Validate modern production task and plugin properties."
        group = "Verification"
        classes.from(sourceSets.main.get().output.classesDirs.asFileTree.matching {
            include("io/github/gradlenexus/publishplugin/internal/modern/**",
                "io/github/gradlenexus/publishplugin/settings/**", "io/github/gradlenexus/publishplugin/tasks/**")
        })
        classpath.from(sourceSets.main.get().compileClasspath, sourceSets.main.get().output)
        launcher.set(project.javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) })
        enableStricterValidation.set(true)
        failOnWarning.set(true)
        outputFile.set(layout.buildDirectory.file("reports/plugin-development/modern-validation-report.json"))
    }

    register<Delete>("resetPublicationProofRepository") {
        delete(publicationProofRepository)
    }
    register("preparePublicationProof") {
        description = "Publish actual plugin and marker publications to an owned file repository for verification."
        group = "Verification"
        dependsOn(withType<PublishToMavenRepository>().matching {
            it.name.endsWith("PublicationToPublicationProofRepository")
        })
    }

    named<ValidatePlugins>("validatePlugins") {
        // Unified Kotlin output includes Settings types compiled against Java 17 Gradle APIs.
        launcher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion = JavaLanguageVersion.of(17)
        })
    }
    named<Jar>("javadocJar") {
        from(dokka.dokkaPublications.named("javadoc").flatMap { it.outputDirectory })
        dependsOn("dokkaGeneratePublicationJavadoc")
    }
    withType<PublishToMavenRepository>().configureEach {
        if (name.endsWith("PublicationToPublicationProofRepository")) dependsOn("resetPublicationProofRepository")
    }
    named<KotlinCompile>("compileKotlin") {
        kotlinJavaToolchain.toolchain.use(project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion = JavaLanguageVersion.of(17)
        })
    }
    named<KotlinCompile>("compileCompatTestKotlin") {
        kotlinJavaToolchain.toolchain.use(project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion = JavaLanguageVersion.of(17)
        })
    }
    withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            // Gradle fully supports running on Java 8: https://docs.gradle.org/current/userguide/compatibility.html,
            // so we should allow users to do that too.
            jvmTarget = JvmTarget.JVM_1_8

            // Suppress "Language version 1.3 is deprecated and its support will be removed in a future version of Kotlin".
            freeCompilerArgs.add("-Xsuppress-version-warnings")

            // Supporting Gradle 6.2+ needs to use Kotlin 1.3.
            // See https://docs.gradle.org/current/userguide/compatibility.html
            // For future maintainer: Kotlin 1.9.0 dropped support for Kotlin 1.3, it'll only support 1.4+.
            // This means Gradle 7.0 will be the lowest supportable version for plugins.
            val usedKotlinVersion = @Suppress("DEPRECATION") KotlinVersion.KOTLIN_1_3

            apiVersion = usedKotlinVersion
            // Theoretically we could use newer language version here,
            // but sadly the @kotlin.Metadata created on the classes would be incompatible with older consumers.
            languageVersion = usedKotlinVersion
        }
    }
    shadowJar {
        exclude("META-INF/maven/**", "META-INF/proguard/**", "META-INF/*.kotlin_module")
        isEnableRelocation = true
        relocationPrefix = "io.github.gradlenexus.publishplugin.shadow"
        manifest { attributes["Implementation-Version"] = project.version }
        archiveClassifier = ""
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
        outputs.doNotCacheIf("E2E tests verify the integration with external system") { true }
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
    named<Test>("test") {
        exclude("io/github/gradlenexus/publishplugin/internal/modern/**", "io/github/gradlenexus/publishplugin/settings/**")
    }
    register<Test>("modernUnitTest") {
        description = "Run direct modern production model and protocol tests on Java 17."
        group = "Verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        include("io/github/gradlenexus/publishplugin/internal/modern/**", "io/github/gradlenexus/publishplugin/settings/**")
        javaLauncher = project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion = JavaLanguageVersion.of(17)
        }
        maxParallelForks = 1
    }
    val packagedClasses = listOf("ProductionCompatibilityTest", "ProductionTransportPackagingTest",
        "ProductionBundleCodecTest", "ProductionWorkflowRecordTest", "ModernGradleBuildTest")
    register<Test>("packagedTest") {
        description = "Run fixed ABI, legacy floor, isolated packaging and strict runner controls."
        packagedClasses.forEach { include("**/$it.class") }
    }
    register<Test>("settingsSmokeTest") {
        description = "Run compact strict Settings store/reuse and admission contracts at both modern pins."
        include("io/github/gradlenexus/publishplugin/modern/**")
        useJUnitPlatform { includeTags("settings-smoke") }
    }
    register<Test>("publicationTest") {
        description = "Verify generated plugin/marker publications, documentation and packaged resolution."
        include("**/ProductionPublicationTest.class")
    }
    register<Test>("settingsTest") {
        description = "Run all surviving broad Settings/native qualification, including the short and publication groups."
        include("io/github/gradlenexus/publishplugin/modern/**")
        (packagedClasses + listOf("ProductionPublicationTest", "RealProductionOssTest")).forEach { exclude("**/$it*") }
        useJUnitPlatform { excludeTags("settings-smoke") }
        dependsOn("modernUnitTest", "packagedTest", "settingsSmokeTest", "publicationTest")
    }
    named("check") {
        dependsOn("modernUnitTest", "packagedTest", "settingsSmokeTest", "validateSettingsPlugins")
    }
    register("legacyCheck") {
        description = "Run ordinary legacy unit, plugin validation and formatting without Settings consumers."
        group = "Verification"
        dependsOn("test", "validatePlugins", "spotlessCheck")
    }
    register<Test>("realProductionOssTest") {
        include("**/RealProductionOssTest.class")
        description = "Run explicit Nexus OSS qualification; requires the disposable --production harness."
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Real repository requests and private signing inputs are invocation-owned") { true }
    }
    val modernTasks = setOf("packagedTest", "settingsSmokeTest", "settingsTest", "publicationTest", "realProductionOssTest")
    withType<Test>().matching { it.name in modernTasks }.configureEach {
        group = "Verification"
        testClassesDirs = compatibilitySources.get().output.classesDirs
        classpath = compatibilitySources.get().runtimeClasspath
        javaLauncher = project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion = JavaLanguageVersion.of(17)
        }
        maxParallelForks = 1
        if (name in setOf("settingsTest", "publicationTest", "realProductionOssTest")) {
            dependsOn("preparePublicationProof")
            systemProperty("modernTest.publicationRepository", publicationProofRepository.get().asFile.absolutePath)
            systemProperty("modernTest.publicationVersion", project.version.toString())
            inputs.dir(publicationProofRepository)
        }
        if (name == "realProductionOssTest") {
            // Only the explicit server task receives disposable harness/signing-file context.
            listOf("NEXUS_PRODUCTION_OSS_ENABLED", "NEXUS_PRODUCTION_OSS_URL", "NEXUS_PRODUCTION_OSS_RUN_DIRECTORY", "LOCAL_E2E_KEY_FILE", "LOCAL_E2E_PASSWORD_FILE").forEach { name ->
                val value = providers.environmentVariable(name).orNull
                if (value == null) environment.remove(name) else environment(name, value)
            }
        }
        systemProperty("modernTest.gradleVersion", "9.8.1")
        systemProperty("modernTest.candidateJar", project.tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
        systemProperty("modernTest.checkoutRoot", rootDir.absolutePath)
        val serverRunDirectory = providers.environmentVariable("NEXUS_PRODUCTION_OSS_RUN_DIRECTORY").orNull
        val workspace = if (name == "realProductionOssTest" && serverRunDirectory != null) {
            File(serverRunDirectory, "production-work")
        } else {
            layout.buildDirectory.dir("verification/$name").get().asFile
        }
        systemProperty("modernTest.workspaceRoot", workspace.absolutePath)
        inputs.file(project.tasks.shadowJar.flatMap { it.archiveFile })
        systemProperty("modernTest.wrapperJar", file("gradle/wrapper/gradle-wrapper.jar").absolutePath)
        systemProperty("modernTest.legacyJavaHome", project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion = JavaLanguageVersion.of(8)
        }.get().metadata.installationPath.asFile.absolutePath)
        environment.remove("CI")
    }
    withType<Test>().matching { it.name.startsWith("compatTest") }.configureEach {
        exclude("io/github/gradlenexus/publishplugin/modern/**")
        systemProperty("plugin.version", project.version)
    }
}

publishing {
    repositories {
        maven {
            name = "publicationProof"
            url = publicationProofRepository.get().asFile.toURI()
        }
    }
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
