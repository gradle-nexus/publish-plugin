import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.diffplug.gradle.spotless") version "3.16.0"
    id("com.github.johnrengelman.shadow") version "4.0.2"
}

group = "de.marcphilipp.gradle"
version = "0.0.1-SNAPSHOT"
description = "Gradle Plugin for publishing to Nexus repositories"

gradlePlugin {
    plugins {
        create("nexus-publish") {
            id = "de.marcphilipp.nexus-publish"
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

val shadowed by configurations.creating
sourceSets["main"].apply {
    compileClasspath = files(compileClasspath, shadowed)
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

tasks {
    val shadowJar by existing(ShadowJar::class) {
        classifier = ""
        configurations = listOf(shadowed)
        exclude("META-INF/maven/**")
        listOf("retrofit2", "okhttp3", "okio", "com").forEach {
            relocate(it, "${project.group}.nexus.shadow.$it")
        }
    }
    "jar" {
        enabled = false
        dependsOn(shadowJar)
    }
    withType<Test> {
        useJUnitPlatform()
    }
}

val sourcesJar by tasks.creating(Jar::class) {
    classifier = "sources"
    from(sourceSets["main"].allSource)
}

val javadocJar by tasks.creating(Jar::class) {
    classifier = "javadoc"
    from(tasks.named("javadoc"))
}

spotless {
    java {
        licenseHeaderFile(file("gradle/license-header.txt"))
    }
}

publishing {
    publications {
        afterEvaluate {
            named<MavenPublication>("pluginMaven") {
                artifact(sourcesJar)
                artifact(javadocJar)
                pom {
                    name.set(project.name)
                    description.set(project.description)
                    inceptionYear.set("2018")
                    url.set("https://github.com/marcphilipp/nexus-publish-plugin")
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
    repositories {
        maven(url = uri(file("$buildDir/repo")))
    }
}
