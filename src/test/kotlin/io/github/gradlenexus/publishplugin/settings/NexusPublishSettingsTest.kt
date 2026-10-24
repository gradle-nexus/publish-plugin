/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.gradlenexus.publishplugin.settings

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.time.Duration

/** Actual ObjectFactory model only; Settings callback ordering and cache tracking stay in TestKit. */
class NexusPublishSettingsTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var settings: NexusPublishSettings
    private lateinit var repository: NexusRepositorySettings

    @BeforeEach
    fun createSettings() {
        val objects = ProjectBuilder.builder().withProjectDir(directory.toFile()).build().objects
        settings = objects.newInstance(NexusPublishSettings::class.java)
        repository = settings.repositories.create("probe")
    }

    @Test
    fun `freeze finalizes every scalar property and excluded project additions without leaking proposed values`() {
        val sensitive = "sensitive-test-value"
        val mutations = linkedMapOf<String, () -> Unit>(
            "useStaging" to { settings.useStaging.set(true) },
            "packageGroup" to { settings.packageGroup.set(sensitive) },
            "repositoryDescription" to { settings.repositoryDescription.set(sensitive) },
            "clientTimeout" to { settings.clientTimeout.set(Duration.ofSeconds(17)) },
            "connectTimeout" to { settings.connectTimeout.set(Duration.ofSeconds(19)) },
            "excludedProjects" to { settings.excludedProjects.set(setOf(":late")) },
            "maxRetries" to { settings.transitionCheckOptions.maxRetries.set(2) },
            "delayBetween" to { settings.transitionCheckOptions.delayBetween.set(Duration.ofSeconds(3)) },
            "nexusUrl" to { repository.nexusUrl.set(URI.create("https://example.invalid/$sensitive")) },
            "snapshotRepositoryUrl" to { repository.snapshotRepositoryUrl.set(URI.create("https://example.invalid/$sensitive")) },
            "username" to { repository.username.set(sensitive) },
            "password" to { repository.password.set(sensitive) },
            "publicationType" to { repository.publicationType.set(NexusRepositorySettings.PublicationType.IVY) },
            "stagingProfileId" to { repository.stagingProfileId.set(sensitive) },
            "stagingRepositoryId" to { repository.stagingRepositoryId.set(sensitive) },
            "allowInsecureProtocol" to { repository.allowInsecureProtocol.set(true) }
        )
        NexusSettingsInternals.freeze(settings)
        mutations.forEach { (name, mutate) ->
            assertThatThrownBy { mutate() }.describedAs(name).isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("final").hasMessageNotContaining(sensitive)
        }
        assertThatThrownBy { settings.excludedProjects.add(":late") }.hasMessageContaining("final")
        assertThat(settings.excludedProjects.get()).isEmpty()
    }

    @Test
    fun `absent optional properties remain absent and reject late set or unset`() {
        NexusSettingsInternals.freeze(settings)
        listOf(settings.useStaging, settings.packageGroup, settings.repositoryDescription, repository.username, repository.password, repository.stagingRepositoryId).forEach {
            assertThat(it.isPresent).isFalse()
            assertThatThrownBy { it.unset() }.hasMessageContaining("final")
        }
        assertThatThrownBy { repository.password.set("late-secret") }.hasMessageContaining("final").hasMessageNotContaining("late-secret")
    }

    @Test
    fun `explicit optional values cannot be unset after freeze`() {
        settings.useStaging.set(false)
        repository.password.set("original")
        repository.stagingRepositoryId.set("original-id")
        NexusSettingsInternals.freeze(settings)
        listOf(settings.useStaging, repository.password, repository.stagingRepositoryId).forEach {
            assertThatThrownBy { it.unset() }.hasMessageContaining("final")
        }
        assertThat(settings.useStaging.get()).isFalse()
        assertThat(repository.password.get()).isEqualTo("original")
        assertThat(repository.stagingRepositoryId.get()).isEqualTo("original-id")
    }

    @Test
    fun `providers are sampled at freeze and their upstream changes do not affect the snapshot`() {
        val objects = ProjectBuilder.builder().withProjectDir(directory.resolve("upstream").toFile()).build().objects
        val upstream = objects.property(String::class.java).value("before")
        repository.username.set(upstream)
        NexusSettingsInternals.freeze(settings)
        upstream.set("after")
        assertThat(repository.username.get()).isEqualTo("before")
        assertThat(upstream.get()).isEqualTo("after")
    }

    @Test
    fun `repository creation removal and clear are rejected after freeze`() {
        NexusSettingsInternals.freeze(settings)
        listOf<() -> Unit>(
            { settings.repositories.create("late") },
            { settings.repositories.remove(repository) },
            { settings.repositories.clear() }
        ).forEach { mutate ->
            assertThatThrownBy { mutate() }.isInstanceOf(GradleException::class.java).hasMessageContaining("NEXUS_SETTINGS_FROZEN: repositories")
        }
    }

    @Test
    fun `explicit Ivy layout preserves ordered patterns and rejects later actions before executing them`() {
        repository.ivyPatternLayout(
            Action { layout ->
                layout.artifact("first/[artifact].[ext]")
                layout.artifact("second/[artifact].[ext]")
                layout.ivy("metadata/ivy.xml")
                layout.setM2compatible(true)
            }
        )
        NexusSettingsInternals.freeze(settings)
        var actionRan = false
        assertThatThrownBy { repository.ivyPatternLayout(Action { actionRan = true }) }
            .hasMessageContaining("NEXUS_SETTINGS_FROZEN: repositories.ivyPatternLayout")
        assertThat(actionRan).isFalse()
        assertThat(repository.isExplicitIvyLayout()).isTrue()
        assertThat(repository.isM2Compatible()).isTrue()
        assertThat(repository.artifactPatterns).containsExactly("first/[artifact].[ext]", "second/[artifact].[ext]")
        assertThat(repository.ivyPatterns).containsExactly("metadata/ivy.xml")
    }

    @Test
    fun `implicit Ivy layout also rejects a first layout action after freeze`() {
        NexusSettingsInternals.freeze(settings)
        assertThatThrownBy { repository.ivyPatternLayout(Action { throw AssertionError("late layout action executed") }) }
            .hasMessageContaining("NEXUS_SETTINGS_FROZEN: repositories.ivyPatternLayout")
        assertThat(repository.isExplicitIvyLayout()).isFalse()
        assertThat(repository.artifactPatterns).isEmpty()
    }
}
