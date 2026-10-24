# Moving Nexus publication configuration to Settings

**Unreleased review draft.** This page describes the implementation in this source tree. It is not a published migration guide or a declaration of completed release qualification. No released plugin version containing Settings support is asserted here. The root publication alias and publication-level omission contract remain pending decisions.

Existing Project-plugin builds retain their tasks, configuration, hooks and supported Gradle/Java versions. Moving to Settings is an explicit migration. The Settings implementation has been qualified locally on Gradle 9.7.1 and 9.8.1 with Java 17, configuration cache and isolated projects. That evidence does not establish a broader runtime range, hosted CI results, Windows/macOS results or licensed Nexus 2 Pro staging support.

## Move repository configuration

Remove application of the public plugin from project build scripts when moving it to settings. Applying the public ID to both Settings and Project fails before legacy task wiring. Each same-build project receives local publication preparation automatically, including root, empty and non-JVM projects. The plugin does not apply Java, publishing or signing to those projects.

Replace `PLUGIN_VERSION_WITH_SETTINGS_SUPPORT` below with an actual plugin build containing this implementation. The token is deliberately not a released version number.

Kotlin `settings.gradle.kts`:

```kotlin
plugins {
    id("io.github.gradle-nexus.publish-plugin") version "PLUGIN_VERSION_WITH_SETTINGS_SUPPORT"
}

rootProject.name = "example"
include("library")

nexusPublishing {
    repositories {
        create("internal") {
            nexusUrl.set(uri("https://nexus.example.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://nexus.example.org/content/repositories/snapshots/"))
        }
    }
}
```

Groovy `settings.gradle`:

```groovy
plugins {
    id 'io.github.gradle-nexus.publish-plugin' version 'PLUGIN_VERSION_WITH_SETTINGS_SUPPORT'
}

rootProject.name = 'example'
include 'library'

nexusPublishing {
    repositories {
        create('internal') {
            nexusUrl.set(uri('https://nexus.example.org/service/local/'))
            snapshotRepositoryUrl.set(uri('https://nexus.example.org/content/repositories/snapshots/'))
        }
    }
}
```

Use your own Nexus 2 URLs. These examples do not describe the Maven Central Portal API or Nexus 3 staging. The Settings repository container supports `create` and `register`; it does not provide the legacy `sonatype` helper. A repository named `sonatype` retains historical endpoint conventions; those conventions do not establish current service availability or Portal compatibility.

Keep publication definitions, generated artifacts, POM/Ivy descriptor customization and signing in the owning project's build script or convention plugin. A Kotlin `library/build.gradle.kts` can retain:

```kotlin
import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
    `maven-publish`
}

group = "org.example"
version = "1.0-SNAPSHOT"

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
        }
    }
}
```

Configure the root's defaults too, even when it has no publications. For this example, root `build.gradle.kts` can contain:

```kotlin
group = "org.example"
version = "1.0-SNAPSHOT"
```

Absent Settings values use the final root-local defaults: `packageGroup` comes from root `group`, and `repositoryDescription` defaults to `group:name:version`. `useStaging` defaults to false when the root version ends in `-SNAPSHOT`, otherwise true. A child's publication version does not override that route.

Explicit `useStaging.set(false)` uses `snapshotRepositoryUrl` even for release coordinates. For direct Nexus 2 OSS release uploads, set it to false and point `snapshotRepositoryUrl` at a hosted release repository. For snapshot uploads, use a snapshot-capable hosted repository. The property's name does not choose the destination from each publication's version. OSS direct deployment does not provide the licensed Nexus 2 Pro staging lifecycle.

## Configuration boundary and credentials

Configure Settings properties, repository membership and Ivy layout directly in the settings script, before the plugin's `settingsEvaluated` snapshot. Providers are sampled and finalized at that boundary. Later setters can fail even when assigning an identical value; later upstream provider changes do not alter finalized policy. Optional absent values remain absent so root conventions can apply. Include projects during settings-script configuration as well.

Credentials conventionally come from the root's `internalUsername` and `internalPassword` String properties for the alias in these examples. Explicit repository `username` and `password` properties/providers override those conventions. Supply both or neither; explicit empty strings are values. Keep secrets out of URLs, task output and publication content. HTTP requires `allowInsecureProtocol.set(true)`. Settings-mode endpoints reject userinfo, query and fragment components. These rules do not change legacy Project-mode credential or endpoint behavior.

## Participation and publication commands

There is no participant list to maintain. Each same-build project exposes Maven and Ivy publication bundles; projects without matching publications provide an empty bundle. `excludedProjects` restricts Nexus selection without removing unrelated native repositories:

```kotlin
nexusPublishing {
    excludedProjects.add(":notForNexus")
}
```

Explicit `:library:publishToInternal` selects the library project. `:publishAllProjectsToInternal` currently aggregates eligible projects. Selected aliases form a union and deduplicate uploads; all selected bundles are validated before staging creation.

**Pending contract decision:** the final meaning of root `:publishToInternal` has not been settled. Do not use it to infer root-only or aggregate behavior from this draft. Publication alias names remain provisional until that decision is incorporated.

Own-project `publish` integration uses that project's publication scope: `:library:publish` selects the library, and root `:publish` selects root-owned publications. Unqualified `publish` follows Gradle task-name selection across publishing projects. The plugin does not force application of a publishing plugin. Running an unrelated individual native repository task or local preparation alone does not enroll a Nexus upload.

On the qualified runtimes, request strict cache and isolation checks while testing the migration:

```sh
./gradlew --configuration-cache --configuration-cache-problems=fail \
  -Dorg.gradle.isolated-projects=true \
  -Dorg.gradle.unsafe.isolated-projects=true \
  :library:publishToInternal
```

Run the same command again to check configuration-cache reuse. These flags do not switch a Project application to Settings mode; the application target selects the implementation. Remote work executes afresh on reuse.

`prepareNexusMavenBundle` and `prepareNexusIvyBundle` perform local preparation only. Their completion does not mean remote publication. Native export tasks are still `PublishToMavenRepository` or `PublishToIvyRepository` tasks, targeting private file repositories. Broad native task hooks therefore run during local export and must not assume they represent the remote HTTP PUT. Keep their file destinations plugin-owned; changing those destinations is unsupported and is rejected by the destination checks or the qualified native task's cache behavior.

**Pending contract decision:** publication-level omission is not settled. A disabled, skipped or failed selected export currently prevents upload instead of accepting a previous bundle. Removing a publication from its model also removes it from other native destinations, so it is not a general replacement for destination-specific filtering. This draft does not claim equivalence to legacy silent skipping.

## Root-owned remote hooks

Use the public interfaces in `io.github.gradlenexus.publishplugin.tasks` for actual root-owned remote work:

| Interface | Purpose |
| --- | --- |
| `NexusRemotePublish` | Remote upload prerequisites, actions and conditions |
| `NexusRepositorySession` | Acquisition/adoption prerequisites and actions, including automatic sessions and explicit initialize/find |
| `FindNexusStagingRepository` | Explicit find operation's `descriptionRegex` |
| `NexusStagingRepositoryTransition` | Close/release and configurable `explicitRepositoryId` |
| `NexusStagingProfileDiagnostic` | Diagnostic profile lookup and diagnostic-only `packageGroup` override |

Each exposes a read-only `repositoryAlias` provider. Do not import implementation classes from `internal` packages. An action attached to a project publication alias runs after its dependencies and cannot act as a pre-upload hook.

For example, root `build.gradle.kts` can configure uploads:

```kotlin
import io.github.gradlenexus.publishplugin.tasks.NexusRemotePublish

val verifyRelease by tasks.registering {
    doLast { logger.lifecycle("Checking release inputs") }
}

tasks.withType<NexusRemotePublish>().configureEach {
    if (repositoryAlias.get() == "internal") {
        dependsOn(verifyRelease)
        doFirst { logger.lifecycle("Before remote upload") }
        doLast { logger.lifecycle("After remote upload") }
    }
}
```

The equivalent root Groovy hook is:

```groovy
import io.github.gradlenexus.publishplugin.tasks.NexusRemotePublish

def verifyRelease = tasks.register('verifyRelease') {
    doLast { logger.lifecycle('Checking release inputs') }
}

tasks.withType(NexusRemotePublish).configureEach {
    if (repositoryAlias.get() == 'internal') {
        dependsOn(verifyRelease)
        doFirst { logger.lifecycle('Before remote upload') }
        doLast { logger.lifecycle('After remote upload') }
    }
}
```

Upload prerequisites and before-actions gate PUTs, but repository acquisition can already have occurred. An after-action can fail after bytes have been uploaded; that failure prevents subsequent selected lifecycle operations from treating the upload as successful. A condition or disabled task is not a successful upload receipt.

Use `NexusRepositorySession` for prerequisites that must guard acquisition. Hooks attached only to the named explicit initialize task do not automatically apply to automatic acquisition:

```kotlin
import io.github.gradlenexus.publishplugin.tasks.NexusRepositorySession

val verifyBeforeAcquisition by tasks.registering {
    doLast { logger.lifecycle("Checking before acquisition") }
}

tasks.withType<NexusRepositorySession>().configureEach {
    if (repositoryAlias.get() == "internal") {
        dependsOn(verifyBeforeAcquisition)
    }
}
```

A shared prerequisite task runs once when explicit acquisition and automatic adoption are selected together. Session actions themselves can run separately on the public acquisition task and the automatic adoption task. They also run for direct nonstaging handle preparation. Legacy initialize actions skip in direct mode, while legacy prerequisite tasks still run. The current implementation therefore has a distinct hook contract; session actions are not one callback per remote create. This migration contract still requires final review. Standalone explicit-ID transitions do not enroll session prerequisites.

For a precompiled convention plugin, add an explicit compile-time dependency on the plugin module at the same feature version. For example, its `build.gradle.kts` needs the following dependency and a repository that supplies that build:

```kotlin
plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

dependencies {
    implementation("io.github.gradle-nexus:publish-plugin:PLUGIN_VERSION_WITH_SETTINGS_SUPPORT")
}
```

Settings plugin resolution does not add the module to `buildSrc` or another convention build's compile classpath. Do not apply the public Project plugin just to expose modern task types.

## Diagnostics and recovery

`retrieveInternalStagingProfile` queries current server profiles, even when `stagingProfileId` is configured for ordinary acquisition; it skips when staging is disabled. A `NexusStagingProfileDiagnostic.packageGroup` override affects only that diagnostic task. `FindNexusStagingRepository.descriptionRegex` affects explicit find, not automatic initialization.

Ordinary close/release tasks skip in direct mode. Explicit initialize/find in Settings direct mode report an unsupported operation instead of creating a staging session. Legacy direct-mode behavior is unchanged.

Independent recovery uses options on the actual operator task:

```sh
./gradlew closeInternalStagingRepository --staging-repository-id repository-id
./gradlew releaseInternalStagingRepository --staging-repository-id repository-id
```

Typed root configuration can set `NexusStagingRepositoryTransition.explicitRepositoryId`. These standalone operations do not schedule publication, artifact preparation, find or creation. Keep a logged created/found repository ID for recovery. Ambiguous creation is not retried automatically; inspect Nexus before choosing recovery. A failed selected acquisition or upload does not admit a later transition.

For configured resume, `NexusRepositorySettings.stagingRepositoryId` supplies an explicit existing repository ID to the publication workflow. It does not fall back to discovery or creation. Keep the configured resume policy distinct from a standalone close/release CLI option.

## Ivy, signing and metadata

Set `publicationType` to `NexusRepositorySettings.PublicationType.IVY` and configure `ivyPatternLayout` in settings when needed. Patterns and M2 compatibility are explicit policy. Any configured custom Ivy layout suppresses Gradle module metadata for Ivy publications throughout the build before generation and signing, including when the selected Ivy repository itself uses the default layout. It does not suppress Maven publication module metadata or silently drop declared ordinary `.module` artifacts.

Keep content changes before signing. Native artifact, descriptor and signature bytes are collected and uploaded without rewriting signed content. Snapshot deployment retains logical publication coordinates; Maven metadata and timestamp allocation follow the repository protocol.

## Example qualification and remaining release work

The API names above were checked against this source tree. Repository tests cover equivalent Kotlin/Groovy Settings application, normal public module resolution, precompiled convention dependencies, typed hooks, settings finalization, selected publication scope and independent recovery. See [public task API tests](../src/compatTest/kotlin/io/github/gradlenexus/publishplugin/modern/ProductionPublicTaskApiTest.kt), [session hook tests](../src/compatTest/kotlin/io/github/gradlenexus/publishplugin/modern/ProductionSessionHookTest.kt) and [settings boundary tests](../src/compatTest/kotlin/io/github/gradlenexus/publishplugin/modern/ProductionSettingsFreezeTest.kt).

These exact page snippets have not been executed as a documentation example suite. The version token, example endpoints, repository alias and project layout must be replaced for a real build. The precompiled dependency example requires a repository containing the chosen unreleased build. Its successful use must not be inferred from the placeholder version.

Local real-server qualification covers Nexus 2 OSS 2.15.2-03 direct deployments on both qualified Gradle runtimes: signed Maven/Ivy releases, custom Ivy layouts, Maven and Ivy snapshots, selected multiproject publications and real plugin markers with independent consumers. Licensed Nexus 2 Pro staging and hosted/platform qualification remain incomplete. Nexus 3 and other publishing backends are follow-ups.

The final root alias and publication omission decisions, complete example qualification and a usable published migration URL remain release prerequisites. A legacy migration warning must not point at an unavailable page. Existing Project builds remain available without a removal deadline or an automatic mode switch.
