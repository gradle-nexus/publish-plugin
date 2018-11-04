# Nexus Publish Plugin

Gradle Plugin that explicitly creates a Staging Repository before publishing to Nexus.

## Usage

The plugin does the following:

- Apply the `maven-publish` plugin
- configure a Maven artifact repository called `nexus` (customizable via the `repositoryName` property)
- creates a `initializeNexusStagingRepository` task that starts a new staging repository in case the project's version does not end with `-SNAPSHOT` (customizable via the `useStaging` property) and sets the URL of the `nexus` repository accordingly. In case of a multi-project build, all subprojects with the same `serverUrl` will use the same staging repository.

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
