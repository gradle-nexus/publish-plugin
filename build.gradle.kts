import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.build-scan") version "1.16"
    id("com.gradle.plugin-publish") version "0.10.0"
    id("com.diffplug.gradle.spotless") version "3.16.0"
    id("com.github.johnrengelman.shadow") version "4.0.2"
    id("io.freefair.lombok") version "2.8.1"
}

buildScan {
    setTermsOfServiceUrl("https://gradle.com/terms-of-service")
    setTermsOfServiceAgree("yes")
}

group = "de.marcphilipp.gradle"
version = "0.0.1-SNAPSHOT"

val readableName = "Nexus Publish Plugin"
description = "Gradle Plugin for publishing to Nexus repositories"
val repoUrl = "https://github.com/marcphilipp/nexus-publish-plugin"

pluginBundle {
    description = project.description
    website = repoUrl
    vcsUrl = repoUrl
    tags = listOf("publishing", "maven", "nexus", "travis")
}

gradlePlugin {
    plugins {
        create("nexus-publish") {
            id = "de.marcphilipp.nexus-publish"
            displayName = readableName
            implementationClass = "de.marcphilipp.gradle.nexus.NexusPublishPlugin"
        }
    }
}

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
tasks.named<JavaCompile>("compileTestJava") {
    sourceCompatibility = "11"
    targetCompatibility = "11"
}

spotless {
    java {
        licenseHeaderFile(file("gradle/license-header.txt"))
    }
}

val shadowed by configurations.creating
sourceSets["main"].apply {
    compileClasspath = files(compileClasspath, shadowed)
}
sourceSets["test"].apply {
    compileClasspath = files(compileClasspath, shadowed)
    runtimeClasspath = files(runtimeClasspath, shadowed)
}

configurations {
    "testImplementation" {
        exclude(group = "junit", module = "junit")
    }
}

dependencies {
    shadowed("com.squareup.retrofit2:retrofit:2.4.0")
    shadowed("com.squareup.retrofit2:converter-gson:2.4.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.3.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.3.1")
    testImplementation("org.junit-pioneer:junit-pioneer:0.3.0")
    testImplementation("com.github.tomakehurst:wiremock:2.19.0")
    testImplementation("org.assertj:assertj-core:3.11.1")
}

val shadowJar by tasks.getting(ShadowJar::class) {
    classifier = ""
    configurations = listOf(shadowed)
    exclude("META-INF/maven/**")
    listOf("retrofit2", "okhttp3", "okio", "com").forEach {
        relocate(it, "${project.group}.nexus.shadow.$it")
    }
}

val pluginUnderTestMetadata by tasks.existing(PluginUnderTestMetadata::class) {
    pluginClasspath.setFrom(shadowJar.archivePath)
}

tasks {
    "jar" {
        enabled = false
        dependsOn(shadowJar)
    }
    withType<Test> {
        useJUnitPlatform()
        dependsOn(shadowJar)
    }
}

val sourcesJar by tasks.creating(Jar::class) {
    classifier = "sources"
    from(sourceSets["main"].allSource)
}

val javadoc by tasks.existing(Javadoc::class) {
    classpath = files(sourceSets["main"].compileClasspath, shadowed)
}

val javadocJar by tasks.creating(Jar::class) {
    classifier = "javadoc"
    from(javadoc)
}

// used by com.gradle.plugin-publish plugin
configurations.archives.artifacts.clear()
artifacts {
    add("archives", shadowJar)
    add("archives", sourcesJar)
    add("archives", javadocJar)
}

publishing {
    publications {
        afterEvaluate {
            named<MavenPublication>("pluginMaven") {
                artifact(sourcesJar)
                artifact(javadocJar)
                pom {
                    name.set(readableName)
                    description.set(project.description)
                    inceptionYear.set("2018")
                    url.set(repoUrl)
                    developers {
                        developer {
                            name.set("Marc Philipp")
                            id.set("marcphilipp")
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
