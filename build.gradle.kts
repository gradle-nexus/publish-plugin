plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.diffplug.gradle.spotless") version "3.16.0"
}

group = "de.marcphilipp.gradle"
version = "0.0.1-SNAPSHOT"

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

dependencies {
    // TODO shadow
    implementation("com.squareup.retrofit2:retrofit:2.4.0")
    implementation("com.squareup.retrofit2:converter-gson:2.4.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.3.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.3.1")
    testImplementation("org.junit-pioneer:junit-pioneer:0.3.0")
    testImplementation("com.github.tomakehurst:wiremock:2.19.0")
    testImplementation("org.assertj:assertj-core:3.11.1")
}

tasks {
    named<JavaCompile>("compileTestJava") {
        sourceCompatibility = "11"
        targetCompatibility = "11"
    }
    withType<Test> {
        useJUnitPlatform()
    }
}

spotless {
    java {
        licenseHeaderFile(file("gradle/license-header.txt"))
    }
}
