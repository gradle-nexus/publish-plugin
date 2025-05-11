# Gradle Nexus Publish Plugin

[![CI Status](https://github.com/gradle-nexus/publish-plugin/workflows/CI/badge.svg)](https://github.com/gradle-nexus/publish-plugin/actions?workflow=CI) [![Gradle Plugin Portal](https://img.shields.io/maven-metadata/v/https/plugins.gradle.org/m2/io.github.gradle-nexus/publish-plugin/maven-metadata.xml.svg?label=Gradle%20Plugin%20Portal)](https://plugins.gradle.org/plugin/io.github.gradle-nexus.publish-plugin)

This Gradle plugin is a turn-key solution for publishing to Nexus. You can use it to publish your artifacts to any Nexus Repository Manager 2.x instance (internal or public). It is great for publishing your open source to Sonatype, and then to [Maven Central][maven-central], in a fully automated fashion.

Vanilla Gradle is great, but it cannot fully automate publications to Nexus. This plugin enables isolation of staging repositories so that you can reliably publish from CI, and each publication uses a brand new, explicitly created staging repo ([more](https://github.com/gradle-nexus/publish-plugin/issues/63)). Moreover, the plugin provides tasks to close and release staging repositories, covering the whole releasing process to Maven Central.

This plugin is intended as a replacement of the [Gradle Nexus Staging Plugin](https://github.com/Codearte/gradle-nexus-staging-plugin/) and [Nexus Publish Plugin](https://github.com/marcphilipp/nexus-publish-plugin) duo. See a dedicated [migration guide](https://github.com/gradle-nexus/publish-plugin/wiki/Migration-from-gradle_nexus_staging-plugin---nexus_publish-plugin-duo).

## Usage

### Applying the plugin

The plugin must be applied to the root project and requires Gradle 6.2 or later. It is important to
set the group and the version to the root project, so the plugin can detect if it is a snapshot
version or not in order to select the correct repository where artifacts will be published.

```groovy
plugins {
    id("io.github.gradle-nexus.publish-plugin") version "«version»"
}

group = "com.example.library"
version = "1.0.0"
```

#### Java compatibility

As of version 2.x, support for JDK <11 is [deprecated](https://github.com/gradle-nexus/publish-plugin/issues/171). The JDK taget compatibility is still set to 8, however, it is encouraged to use the latest possible Java version (e.g. 21+). As being deprecated, support for JDK <11 might be dropped in a future minor plugin version (i.e. 2.x).

### Publishing to Maven Central via Sonatype Central

In order to publish to Maven Central (aka the Central Repository or just Central) via [Sonatype Central], you need to add the `sonatype()` repository like in the example below. Its `nexusUrl` and `snapshotRepositoryUrl` values must be configured as below, see [Sonatype's site](https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/#configuration).

```groovy
nexusPublishing {
    repositories {
        sonatype()
    }
    repositories {
        // see https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/#configuration
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}
```

You need to set your Nexus credentials. To increase security, it is advised to use the [user token's username and password pair](https://blog.solidsoft.pl/2015/09/08/deploy-to-maven-central-using-api-key-aka-auth-token/) (instead of regular username and password). Those values should be set as the `sonatypeUsername` and `sonatypePassword` project properties, e.g. in `~/.gradle/gradle.properties` or via the `ORG_GRADLE_PROJECT_sonatypeUsername` and `ORG_GRADLE_PROJECT_sonatypePassword` environment variables.

Alternatively (e.g. for local testing), you can configure credentials in the `sonatype` block directly:

```groovy
nexusPublishing {
    repositories {
        sonatype {
            username = "your-user-token-username"
            password = "your-user-token-password"
        }
    }
}
```
### ~~Publishing to Maven Central via Sonatype OSSRH~~

**DEPRECATED. [OSSRH reaches end-of-life on June 30, 2025!](https://central.sonatype.org/news/20250326_ossrh_sunset/)** You should migrate to Sonatype Central. The migration is straightforward, only [configure the correct URLs](#publishing-to-maven-central-via-sonatype-central) ↑↑↑.

<details>
<summary>
Deprecated configuration for historical purpose only.
</summary>
<br />
In order to publish to Maven Central (aka the Central Repository or just Central) via Sonatype's OSSRH Nexus, you simply need to add the `sonatype()` repository like in the example below. Its `nexusUrl` and `snapshotRepositoryUrl` values are pre-configured.

```groovy
nexusPublishing {
    repositories {
        sonatype()
    }
}
```

**Important**. Users registered in Sonatype after [24 February 2021](https://central.sonatype.org/news/20210223_new-users-on-s01/) need to customize the following URLs:

```groovy
nexusPublishing {
    repositories {
        sonatype {  //only for users registered in Sonatype after 24 Feb 2021
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
        }
    }
}
```
(if unsure check the server address in a corresponding ticket for your project in Sonatype's Jira)

In addition, for both groups of users, you need to set your Nexus credentials. To increase security, it is advised to use the [user token's username and password pair](https://blog.solidsoft.pl/2015/09/08/deploy-to-maven-central-using-api-key-aka-auth-token/) (instead of regular username and password). Those values should be set as the `sonatypeUsername` and `sonatypePassword` project properties, e.g. in `~/.gradle/gradle.properties` or via the `ORG_GRADLE_PROJECT_sonatypeUsername` and `ORG_GRADLE_PROJECT_sonatypePassword` environment variables.

Alternatively (e.g. for local testing), you can configure credentials in the `sonatype` block directly:

```groovy
nexusPublishing {
    repositories {
        sonatype {
            username = "your-user-token-username"
            password = "your-user-token-password"
        }
    }
}
```

</details>

#### Configure [Signing](https://docs.gradle.org/current/userguide/signing_plugin.html#sec:signing_publications) ####

Add the signing plugin:
```kotlin
plugins {
    // ...
    signing
}
```
then configure:
```kotlin
signing {
    sign(publishing.publications["mavenJava"])
}
```

#### Publishing with Ivy ####

There are cases where it may be necessary to use the `ivy-publish` plugin instead of `maven-publish`.
For example, when publishing Sbt plugins the directory structure needs to be customized which is only possible with Gradle's `IvyArtifactRepository`.

In such cases, you need to apply the `ivy-publish` plugin and configure the `publicationType` fore each `NexusRepository`, that should be ivy compatible, to `IVY` (default is `MAVEN`).

In case of Ivy publishing, because of compatibility with Sonatype the nexus repository layout will be used by default

```groovy
nexusPublishing {
    respositories {
        ivyRepository {
            publicationType = io.github.gradlenexus.publishplugin.NexusRepository.PublicationType.IVY
        }
    }
}
```

Or use the kotlin DSL:

```kotlin
nexusPublishing {
    respositories {
        register("ivyRepository") {
            publicationType.set(io.github.gradlenexus.publishplugin.NexusRepository.PublicationType.IVY)
        }
    }    
}
```

##### Using Ivy repositories with different artifact patterns ####

In case of ivy it's possible to override the default artifact pattern that is used, which is the Maven pattern due to compatibility reasons with sonatype

To change the pattern of artifacts and ivy files configure the `ivyPatternLayout` on each repository that should be used with this layout with:

```groovy
nexusPublishing {
    respositories {
        ivyRepository {
            ivyPatternLayout {
                artifact "[organisation]/[module]_foo/[revision]/[artifact]-[revision](-[classifier])(.[ext])"
                m2compatible = true
            }
        }
    }
}
```

Or use the kotlin DSL:

```kotlin
nexusPublishing {
    respositories {
        register("ivyRepository") {
            ivyPatternLayout {
                ivyPatternLayout {
                    artifact("[organisation]/[module]_foo/[revision]/[artifact]-[revision](-[classifier])(.[ext])")
                    m2compatible = true
                }
            }
        }
    }
}
```

#### Add Metadata ####

See the [Maven publishing page in Gradle documentation](https://docs.gradle.org/current/userguide/publishing_maven.html#publishing_maven:publications) for what these mean, default values, and how to override coordinates (`groupId:artifactId:version`).

```kotlin
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("<<Component Name>>")
                description.set("<<Component Description>>")
                url.set("<<Component URL>>")
                licenses {
                    license {
                        name.set("<<License Name>>")
                        url.set("<<License URL>>")
                    }
                }
                developers {
                    developer {
                        id.set("<<Developer ID>>")
                        name.set("<<Developer Name>>")
                        email.set("<<Developer Email>>")
                    }
                }
                scm {
                    connection.set("<<SCM Connection URL>>")
                    developerConnection.set("<<SCM Dev Connection URL>>")
                    url.set("<<Source URL>>")
                }
            }
        }
    }
}
```

Finally, call `publishToSonatype closeAndReleaseSonatypeStagingRepository` to publish all publications to Sonatype's OSSRH Nexus and subsequently close and release the corresponding staging repository, effectively making the artifacts available in Maven Central (usually after a few minutes).

Please bear in mind that - especially on the initial project publishing to Maven Central - it might be wise to call just `publishToSonatype closeSonatypeStagingRepository` and manually verify that the artifacts placed in the closed staging repository in Nexus looks ok. After that, the staging repository might be dropped (if needed) or manually released from the Nexus UI.  

#### Publishing and closing in different Gradle invocations

You might want to publish and close in different Gradle invocations. For example, you might want to publish from CI
and close and release from your local machine.
An alternative use case is to publish and close the repository and let others review and preview the publication before
the release.

The use case is possible by using `find${repository.name.capitalize()}StagingRepository` (e.g. `findSonatypeStagingRepository`) task.
By default, `initialize${repository.name.capitalize()}StagingRepository` task adds a description to the repository which defaults to
`$group:$module:$version` of the root project, so the repository can be found later using the same description.

The description can be customized via:
* `io.github.gradlenexus.publishplugin.NexusPublishExtension.getRepositoryDescription` property (default: `$group:$module:$version` of the root project)
* `io.github.gradlenexus.publishplugin.InitializeNexusStagingRepository.repositoryDescription` property
* `io.github.gradlenexus.publishplugin.FindStagingRepository.descriptionRegex` property (regex, default: `"\\b" + Regex.escape(repositoryDescription) + "(\\s|$)"`)

So the steps to publish and release in different Gradle invocations are:
1. Publish the artifacts to the staging repository: `./gradlew publishToSonatype`
2. Close the staging repository: `./gradlew findSonatypeStagingRepository closeSonatypeStagingRepository`
3. Release the staging repository: `./gradlew findSonatypeStagingRepository releaseSonatypeStagingRepository`

(in the above example, steps 1 and 2 could be also combined into `./gradlew publishToSonatype closeSonatypeStagingRepository`, to make only the releasing done in a separate step)

### Summary Tasks

If you declare multiple repositories, you get a separate set of tasks for each of the repositories.
If you for example declared the repositories `sonatype` and `otherNexus`, you get these tasks:
- `closeSonatypeStagingRepository`
- `closeOtherNexusStagingRepository`
- `releaseSonatypeStagingRepository`
- `releaseOtherNexusStagingRepository`
- `closeAndReleaseSonatypeStagingRepository`
- `closeAndReleaseOtherNexusStagingRepository`

For convenience there are also summary tasks generated, that group the tasks for the different repositories, which are
- `closeStagingRepositories`
- `releaseStagingRepositories`
- `closeAndReleaseStagingRepositories`

In the typical use-case, which is only one repository for publishing to Maven Central, these tasks still are useful,
especially if you are using Kotlin DSL build scripts, because those summary tasks are always added, independent of
declared repositories. Due to that there are type-safe accessors generated that can be used conveniently for task dependencies.

### Full example

#### Groovy DSL

```groovy
plugins {
    id "java-library"
    id "maven-publish"
    id "signing"
    id "io.github.gradle-nexus.publish-plugin" version "«version»"
}

publishing {
    publications {
        mavenJava(MavenPublication) {
            from(components.java)

            pom {
                name = "<<Component Name>>"
                description = "<<Component Description>>"
                url = "<<Component URL>>"
                licenses {
                    license {
                        name = "<<License Name>>"
                        url = "<<License URL>>"
                    }
                }
                developers {
                    developer {
                        id = "<<Developer ID>>"
                        name = "<<Developer Name>>"
                        email = "<<Developer Email>>"
                    }
                }
                scm {
                    connection = "<<SCM Connection URL>>"
                    developerConnection = "<<SCM Dev Connection URL>>"
                    url = "<<Source URL>>"
                }
            }
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

signing {
    sign publishing.publications.mavenJava
}
```

#### Kotlin DSL

```kotlin
plugins {
    `java-library`
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin") version "«version»"
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("<<Component Name>>")
                description.set("<<Component Description>>")
                url.set("<<Component URL>>")
                licenses {
                    license {
                        name.set("<<License Name>>")
                        url.set("<<License URL>>")
                    }
                }
                developers {
                    developer {
                        id.set("<<Developer ID>>")
                        name.set("<<Developer Name>>")
                        email.set("<<Developer Email>>")
                    }
                }
                scm {
                    connection.set("<<SCM Connection URL>>")
                    developerConnection.set("<<SCM Dev Connection URL>>")
                    url.set("<<Source URL>>")
                }
            }
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

signing {
    sign(publishing.publications["mavenJava"])
}
```

### HTTP Timeouts

You can configure the `connectTimeout` and `clientTimeout` properties on the `nexusPublishing` extension to set the connect and read/write timeouts (both default to 5 minutes). Good luck!

### Retries for state transitions

When closing or releasing a staging repository the plugin first initiates the transition and then retries a configurable number of times with a configurable delay after each attempt.
Both can be configured like this:

#### Groovy DSL

```gradle
import java.time.Duration

nexusPublishing {
    transitionCheckOptions {
        maxRetries = 100
        delayBetween = Duration.ofSeconds(5)
    }
}
```

#### Kotlin DSL

```gradle
import java.time.Duration

nexusPublishing {
    transitionCheckOptions {
        maxRetries.set(100)
        delayBetween.set(Duration.ofSeconds(5))
    }
}
```

- `maxRetries` default value is 60.
- `delayBetween` default value is 10 seconds.

### Compatibility

| Nexus Version                                      | Compatible?        |
|----------------------------------------------------|--------------------|
| Sonatype [Maven Central Repository][maven-central] | Yes                |
| Sonatype Nexus Repository Manager 2.x              | Yes                |
| Sonatype Nexus Repository Manager 3.x              | [No][nexus-compat] / https://github.com/gradle-nexus/publish-plugin/issues/320 |

### Troubleshooting

Log into your staging repository account. On the left side, expand "Build Promotion", then click "Staging Repositories".
Here, you should see your newly created repositories. You can click on one of them, then select the "Activity" tab to
see any errors that have occurred.

---

## Behind the scenes

The plugin does the following:

- configure a Maven artifact repository for each repository defined in the `nexusPublishing { repositories { ... } }` block in each subproject that applies the `maven-publish` or the `ivy-publish` plugin
- creates a `retrieve{repository.name.capitalize()}StagingProfile` task that retrieves the staging profile id from the remote Nexus repository. This is a diagnostic task to enable setting the configuration property `stagingProfileId` in  `nexusPublishing { repositories { myRepository { ... } } }`. Specifying the configuration property rather than relying on the API call is considered a performance optimization.  
- create a `initialize${repository.name.capitalize()}StagingRepository` task that starts a new staging repository in case the project's version does not end with `-SNAPSHOT` (customizable via the `useStaging` property) and sets the URL of the corresponding Maven artifact repository accordingly. In case of a multi-project build, all subprojects with the same `nexusUrl` will use the same staging repository.
- make all publishing tasks for each configured repository depend on the `initialize${repository.name.capitalize()}StagingRepository` task
- create a `publishTo${repository.name.capitalize()}` lifecycle task that depends on all publishing tasks for the corresponding Maven artifact repository
- create `close${repository.name.capitalize()}StagingRepository` and `release${repository.name.capitalize()}StagingRepository` tasks that must run after the all publishing tasks
  - to simplify the common use case also a `closeAndRelease${repository.name.capitalize()}StagingRepository` task is created which depends on all the `close*` and `release*` tasks for a given repository

---

## Historical background

In 2015, [Marcin Zajączkowski](https://blog.solidsoft.pl/) created [gradle-nexus-staging-plugin](https://github.com/Codearte/gradle-nexus-staging-plugin/) which was providing an ability to close and release staging repositories in Nexus repository manager. It opened an opportunity to manage releasing Gradle projects to Maven Central completely from code. Over the years, it has been adopted by various projects across the globe, however there was a small problem. Due to technical limitations in the publishing process in Gradle, it was required to use heuristics to track implicitly created staging repositories, what often failed for multiple repositories in a given state. The situation became even worse when Travis changed its network architecture in late 2019 and the majority of releases started to fail.
Here, [Marc Philipp](https://github.com/marcphilipp/) entered the stage who created [Nexus Publish Plugin](https://github.com/marcphilipp/nexus-publish-plugin) which was enriching the publishing mechanism in Gradle to explicitly create staging repositories and publish (upload) artifacts directly to it.

Those two plugins nicely worked together, providing a reliable way to handle publishing artifacts to Maven Central (and to other Nexus instances in general). However, the need of using two plugins was very often confusing for users. As a result, an idea to create one plugin mixing the aforementioned capabilities emerged. It materialized in 2020/2021 as Gradle Nexus Publish Plugin, an effect of combined work of Marc and Marcin, supported by a pack of [contributors](https://github.com/gradle-nexus/publish-plugin/graphs/contributors).      

[nexus-compat]: https://help.sonatype.com/en/nexus-repository-2-vs--nexus-repository-3-feature-equivalency-matrix.html#:~:text=API%20documentation.-,Note,-Note%20that%20NexusRepository
[maven-central]: https://central.sonatype.com/
