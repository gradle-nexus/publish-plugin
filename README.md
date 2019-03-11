# Nexus Publish Plugin

[![Build Status](https://travis-ci.org/marcphilipp/nexus-publish-plugin.svg?branch=master)](https://travis-ci.org/marcphilipp/nexus-publish-plugin)
[![Gradle Plugin Portal](https://img.shields.io/maven-metadata/v/https/plugins.gradle.org/m2/de.marcphilipp.gradle/nexus-publish-plugin/maven-metadata.xml.svg?label=gradlePluginPortal)](https://plugins.gradle.org/plugin/de.marcphilipp.nexus-publish)

Gradle Plugin that explicitly creates a Staging Repository before publishing to Nexus. This solves the problem that frequently occurs when uploading to Nexus from Travis, namely split staging repositories.

## Usage

The plugin does the following:

- Apply the `maven-publish` plugin
- configure a Maven artifact repository called `nexus` (customizable via the `repositoryName` property)
- create a `initializeNexusStagingRepository` task that starts a new staging repository in case the project's version does not end with `-SNAPSHOT` (customizable via the `useStaging` property) and sets the URL of the `nexus` repository accordingly. In case of a multi-project build, all subprojects with the same `serverUrl` will use the same staging repository.
- if the [`io.codearte.nexus-staging` plugin](https://github.com/Codearte/gradle-nexus-staging-plugin) is applied on the root project, the `stagingRepositoryId` on its extension is set to the id of the newly created staging repository, this way it does not depend on exactly one open staging repository being available.
- make all publishing tasks for the `nexus` repository depend on the `initializeNexusStagingRepository` task.
- create a `publishToNexus` lifecycle task that depends on all publishing tasks for the `nexus` repository.

### Groovy DSL

```gradle
plugins {
    id "java-library"
    id "de.marcphilipp.nexus-publish" version "0.2.0"
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components.java)
        }
    }
}

nexusPublishing {
    serverUrl = uri("https://your-server.com/staging") // defaults to https://oss.sonatype.org/service/local/
    snapshotRepositoryUrl = uri("https://your-server.com/snapshots") // defaults to https://oss.sonatype.org/content/repositories/snapshots/
    username = "your-username" // defaults to project.properties["nexusUsername"]
    password = "your-password" // defaults to project.properties["nexusPassword"]
}
```

### Kotlin DSL

```kotlin
plugins {
    `java-library`
    id("de.marcphilipp.nexus-publish") version "0.2.0"
}

publishing {
    publications {
        mavenJava(MavenPublication) {
            from(components["java"])
        }
    }
}

nexusPublishing {
    serverUrl.set(uri("https://your-server.com/staging")) // defaults to https://oss.sonatype.org/service/local/
    snapshotRepositoryUrl.set(uri("https://your-server.com/snapshots")) // defaults to https://oss.sonatype.org/content/repositories/snapshots/
    username.set("your-username") // defaults to project.properties["nexusUsername"]
    password.set("your-password") // defaults to project.properties["nexusPassword"]
}
```

If the [`io.codearte.nexus-staging` plugin](https://github.com/Codearte/gradle-nexus-staging-plugin) is applied on the root project, the following default values change:

| Property            | Default value                                |
| ------------------- | -------------------------------------------- |
| `packageGroup`      | `rootProject.nexusStaging.packageGroup`      |
| `stagingProfileId`  | `rootProject.nexusStaging.stagingProfileId`  |
| `username`          | `rootProject.nexusStaging.username`          |
| `password`          | `rootProject.nexusStaging.password`          |

This reuses the values specified for the `nexusStaging` block, so you don't have to specify them twice.
