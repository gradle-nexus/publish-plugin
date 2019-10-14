# Nexus Publish Plugin

[![CI Status](https://github.com/gradle-nexus/publish-plugin/workflows/CI/badge.svg)](https://github.com/gradle-nexus/publish-plugin/actions?workflow=CI) [![Gradle Plugin Portal](https://img.shields.io/maven-metadata/v/https/plugins.gradle.org/m2/io.github.gradle-nexus/publish-plugin/maven-metadata.xml.svg?label=Gradle%20Plugin%20Portal)](https://plugins.gradle.org/plugin/io.github.gradle-nexus.publish-plugin)

Gradle Plugin that explicitly creates a Staging Repository before publishing to Nexus. This solves the problem that frequently occurs when uploading to Nexus from Travis, namely split staging repositories.

## Usage

The plugin does the following:

- Apply the `maven-publish` plugin
- configure a Maven artifact repository for each repository defined in the `nexusPublishing { repositories { ... } }` block
- create a `initialize${repository.name.capitalize()}StagingRepository` task that starts a new staging repository in case the project's version does not end with `-SNAPSHOT` (customizable via the `useStaging` property) and sets the URL of the corresponding Maven artifact repository accordingly. In case of a multi-project build, all subprojects with the same `nexusUrl` will use the same staging repository.
- if the [`io.codearte.nexus-staging` plugin](https://github.com/Codearte/gradle-nexus-staging-plugin) is applied on the root project, the `stagingRepositoryId` on its extension is set to the id of the newly created staging repository, this way it does not depend on exactly one open staging repository being available.
- make all publishing tasks for each configured repository depend on the `initialize${repository.name.capitalize()}StagingRepository` task.
- create a `publishTo${repository.name.capitalize()}` lifecycle task that depends on all publishing tasks for the corresponding Maven artifact repository.

### Publishing to Maven Central via Sonatype OSSRH

In order to publish to Maven Central via Sonatype's OSSRH Nexus, you simply need to add the `sonatype()` repository like in the example below. It's `nexusUrl` and `snapshotRepositoryUrl` are pre-configured.

```gradle
nexusPublishing {
    repositories {
        sonatype()
    }
}
```

In addition, you need to set the `sonatypeUsername` and `sonatypePassword` project properties, e.g. in `~/.gradle/gradle.properties`. Alternatively, you can configure username and password in the `sonatype` block:

```gradle
nexusPublishing {
    repositories {
        sonatype {
            username = "your-username"
            password = "your-password"
        }
    }
}
```

Finally, call `publishToSonatype` to publish all publications to Sonatype's OSSRH Nexus.

### Full example

#### Groovy DSL

```gradle
plugins {
    id "java-library"
    id "io.github.gradle-nexus.publish-plugin" version "0.1.0-SNAPSHOT"
}

publishing {
    publications {
        mavenJava(MavenPublication) {
            from(components.java)
        }
    }
}

nexusPublishing {
    repositories {
        myNexus {
            nexusUrl = uri("https://your-server.com/staging")
            snapshotRepositoryUrl = uri("https://your-server.com/snapshots")
            username = "your-username" // defaults to project.properties["myNexusUsername"]
            password = "your-password" // defaults to project.properties["myNexusPassword"]
        }
    }
}
```

#### Kotlin DSL

```kotlin
plugins {
    `java-library`
    id("io.github.gradle-nexus.publish-plugin") version "0.1.0-SNAPSHOT"
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}

nexusPublishing {
    repositories {
        create("myNexus") {
            nexusUrl.set(uri("https://your-server.com/staging"))
            snapshotRepositoryUrl.set(uri("https://your-server.com/snapshots"))
            username.set("your-username") // defaults to project.properties["myNexusUsername"]
            password.set("your-password") // defaults to project.properties["myNexusPassword"]
        }
    }
}
```

### Integration with the Nexus Staging Plugin

If the [`io.codearte.nexus-staging` plugin](https://github.com/Codearte/gradle-nexus-staging-plugin) is applied on the root project, the following default values change:

| Property            | Default value                                |
| ------------------- | -------------------------------------------- |
| `packageGroup`      | `rootProject.nexusStaging.packageGroup`      |
| `stagingProfileId`  | `rootProject.nexusStaging.stagingProfileId`  |
| `username`          | `rootProject.nexusStaging.username`          |
| `password`          | `rootProject.nexusStaging.password`          |

This reuses the values specified for the `nexusStaging` block, so you don't have to specify them twice.

### HTTP Timeouts

You can configure the `connectTimeout` and `clientTimeout` properties on the `nexusPublishing` extension to set the connect and read/write timeouts (both default to 1 minute). Good luck!
