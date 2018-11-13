# Nexus Publish Plugin

[![Build Status](https://travis-ci.org/marcphilipp/nexus-publish-plugin.svg?branch=master)](https://travis-ci.org/marcphilipp/nexus-publish-plugin)

Gradle Plugin that explicitly creates a Staging Repository before publishing to Nexus. This solves the problem that frequently occurs when uploading to Nexus from Travis, namely split staging repositories.

## Usage

The plugin does the following:

- Apply the `maven-publish` plugin
- configure a Maven artifact repository called `nexus` (customizable via the `repositoryName` property)
- create a `initializeNexusStagingRepository` task that starts a new staging repository in case the project's version does not end with `-SNAPSHOT` (customizable via the `useStaging` property) and sets the URL of the `nexus` repository accordingly. In case of a multi-project build, all subprojects with the same `serverUrl` will use the same staging repository.
- make all publishing tasks for the `nexus` repository depend on the `initializeNexusStagingRepository` task.
- create a `publishToNexus` lifecycle task that depends on all publishing tasks for the `nexus` repository.

```gradle
plugins {
    id("java-library")
    id("de.marcphilipp.nexus-publish") version "0.0.1-SNAPSHOT"
}

publishing {
    publications {
        mavenJava(MavenPublication) {
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

If the [`io.codearte.nexus-staging` plugin](https://github.com/Codearte/gradle-nexus-staging-plugin) is applied on the root project, the following default values change:
```gradle
nexusPublishing {
    packageGroup = rootProject.nexusStaging.packageGroup
    stagingProfileId = rootProject.nexusStaging.stagingProfileId
    username = rootProject.nexusStaging.username
    password = rootProject.nexusStaging.password
}
```
This reuses the values specified for the `nexusStaging` block, so you don't have to specify them twice.
