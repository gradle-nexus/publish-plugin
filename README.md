# Nexus Publish Plugin

[![CI Status](https://github.com/gradle-nexus/publish-plugin/workflows/CI/badge.svg)](https://github.com/gradle-nexus/publish-plugin/actions?workflow=CI) [![Gradle Plugin Portal](https://img.shields.io/maven-metadata/v/https/plugins.gradle.org/m2/io.github.gradle-nexus/publish-plugin/maven-metadata.xml.svg?label=Gradle%20Plugin%20Portal)](https://plugins.gradle.org/plugin/io.github.gradle-nexus.publish-plugin)

Gradle Plugin that explicitly creates a _staging repository_ before publishing to Nexus. This solves the problem that frequently occurs when uploading to Nexus from Travis, namely split staging repositories. Moreover, the plugin provides tasks to close and release staging repositories.

## Usage

### Applying the plugin

The plugin must be applied to the root project and requires Gradle 5.0 or later.

```gradle
plugins {
    id("io.github.gradle-nexus.publish-plugin") version "«version»"
}
```

### Publishing to Maven Central via Sonatype OSSRH

In order to publish to Maven Central (aka the Central Repository or just Central) via Sonatype's OSSRH Nexus, you simply need to add the `sonatype()` repository like in the example below. It's `nexusUrl` and `snapshotRepositoryUrl` are pre-configured.

```gradle
nexusPublishing {
    repositories {
        sonatype()
    }
}
```

In addition, you need to set the `sonatypeUsername` and `sonatypePassword` project properties, e.g. in `~/.gradle/gradle.properties` or via the `ORG_GRADLE_PROJECT_sonatypeUsername` and `ORG_GRADLE_PROJECT_sonatypePassword` environment variables.

Alternatively, you can configure username and password in the `sonatype` block:

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

Finally, call `publishToSonatype closeAndReleaseRepository` to publish all publications to Sonatype's OSSRH Nexus and subsequently close and release the corresponding staging repository, effectively making the artifacts available in Maven Central (usually after a few minutes).

Please bear in mind that - especially on the initial project publishing to Maven Central - it might be wise to call just `publishToSonatype closeSonatypeReleaseRepository` and manually verify that the artifacts placed in the closed staging repository in Nexus looks ok. After that, the staging repository might be dropped (if needed) or manually released from the Nexus UI.  

### Full example

#### Groovy DSL

```gradle
plugins {
    id "java-library"
    id "maven-publish"
    id "io.github.gradle-nexus.publish-plugin" version "«version»"
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
    `maven-publish`
    id("io.github.gradle-nexus.publish-plugin") version "«version»"
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

### HTTP Timeouts

You can configure the `connectTimeout` and `clientTimeout` properties on the `nexusPublishing` extension to set the connect and read/write timeouts (both default to 5 minutes). Good luck!

---

## Behind the scenes

The plugin does the following:

- configure a Maven artifact repository for each repository defined in the `nexusPublishing { repositories { ... } }` block in each subproject that applies the `maven-publish` plugin
- create a `initialize${repository.name.capitalize()}StagingRepository` task that starts a new staging repository in case the project's version does not end with `-SNAPSHOT` (customizable via the `useStaging` property) and sets the URL of the corresponding Maven artifact repository accordingly. In case of a multi-project build, all subprojects with the same `nexusUrl` will use the same staging repository.
- make all publishing tasks for each configured repository depend on the `initialize${repository.name.capitalize()}StagingRepository` task
- create a `publishTo${repository.name.capitalize()}` lifecycle task that depends on all publishing tasks for the corresponding Maven artifact repository
- create `close${repository.name.capitalize()}StagingRepository` and `release${repository.name.capitalize()}StagingRepository` tasks that must run after the all publishing tasks
  - to simplify the common use case also a `closeAndRelease${repository.name.capitalize()}StagingRepository` task is created which depends on all the `close*` and `release*` tasks for a given repository

