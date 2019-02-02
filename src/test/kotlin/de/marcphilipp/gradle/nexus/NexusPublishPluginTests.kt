/*
 * Copyright 2018 the original author or authors.
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

package de.marcphilipp.gradle.nexus

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import com.google.gson.Gson
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.TaskOutcome.SKIPPED
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path

@ExtendWith(WireMockExtension::class)
class NexusPublishPluginTests {

    companion object {

        private const val STAGING_PROFILE_ID = "someProfileId"
        private const val STAGED_REPOSITORY_ID = "orgexample-42"

        @JvmStatic
        private fun gradleVersionAndSettings(): Iterable<Arguments> {
            return listOf(
                    Arguments.of("4.10.3", "// no extra settings"),
                    Arguments.of("4.10.3", "enableFeaturePreview('STABLE_PUBLISHING')"),
                    Arguments.of("5.0", "// no extra settings"),
                    Arguments.of("5.1.1", "// no extra settings"),
                    Arguments.of("5.2-rc-1", "// no extra settings")
            )
        }
    }

    private val gson = Gson()

    private val gradleRunner = GradleRunner.create().withPluginClasspath()

    private val pluginClasspathAsString: String
        get() = gradleRunner.pluginClasspath.joinToString(", ") { "'${it.absolutePath.replace('\\', '/')}'" }

    @ParameterizedTest
    @MethodSource("gradleVersionAndSettings")
    fun `publishToNexus depends on correct tasks`(gradleVersion: String, extraSettings: String, @TempDir projectDir: Path) {
        Files.writeString(projectDir.resolve("settings.gradle"), """
            rootProject.name = 'sample'
            $extraSettings
        """)
        Files.writeString(projectDir.resolve("build.gradle"), """
            plugins {
                id('java-library')
                id('de.marcphilipp.nexus-publish')
            }
            group = 'org.example'
            version = '0.0.1'
            publishing {
                publications {
                    mavenJava(MavenPublication) {
                        from(components.java)
                    }
                }
                repositories {
                    maven {
                        name 'someOtherRepo'
                        url 'someOtherRepo'
                    }
                }
            }
            // use this instead of --dry-run to get the tasks in the result for verification
            tasks.all { enabled false }
        """)

        val result = runGradleBuild(gradleVersion, projectDir, "publishToNexus")

        assertSkipped(result, ":publishToNexus")
        assertSkipped(result, ":publishMavenJavaPublicationToNexusRepository")
        assertNotConsidered(result, ":publishMavenJavaPublicationToSomeOtherRepoRepository")
    }

    @ParameterizedTest
    @MethodSource("gradleVersionAndSettings")
    fun `publishes to Nexus`(gradleVersion: String, extraSettings: String, @TempDir projectDir: Path, server: WireMockServer) {
        Files.writeString(projectDir.resolve("settings.gradle"), """
            rootProject.name = 'sample'
            $extraSettings
        """)
        Files.writeString(projectDir.resolve("build.gradle"), """
            plugins {
                id('java-library')
                id('de.marcphilipp.nexus-publish')
            }
            group = 'org.example'
            version = '0.0.1'
            publishing {
                publications {
                    mavenJava(MavenPublication) {
                        from(components.java)
                    }
                }
            }
            nexusPublishing {
                serverUrl = uri('${server.baseUrl()}')
                username = 'username'
                password = 'password'
            }
        """)

        stubStagingProfileRequest(server, "/staging/profiles", mapOf("id" to STAGING_PROFILE_ID, "name" to "org.example"))
        stubCreateStagingRepoRequest(server, "/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)
        expectArtifactUploads(server, "/staging/deployByRepositoryId/$STAGED_REPOSITORY_ID")

        val result = runGradleBuild(gradleVersion, projectDir, "publishToNexus")

        assertSuccess(result, ":initializeNexusStagingRepository")
        assertUploadedToStagingRepo(server, "/org/example/sample/0.0.1/sample-0.0.1.pom")
        assertUploadedToStagingRepo(server, "/org/example/sample/0.0.1/sample-0.0.1.jar")
    }

    @ParameterizedTest
    @MethodSource("gradleVersionAndSettings")
    fun `can be used with lazily applied Gradle Plugin Development Plugin`(gradleVersion: String, extraSettings: String, @TempDir projectDir: Path, server: WireMockServer) {
        Files.writeString(projectDir.resolve("settings.gradle"), """
            rootProject.name = 'sample'
            $extraSettings
            include 'gradle-plugin'
        """)

        Files.writeString(projectDir.resolve("build.gradle"), """
            buildscript {
                dependencies {
                    classpath files($pluginClasspathAsString)
                }
            }
            allprojects {
                plugins.withId('maven-publish') {
                    project.apply plugin: 'de.marcphilipp.nexus-publish'
                    project.extensions.configure('nexusPublishing') { ext ->
                        ext.serverUrl = uri('${server.baseUrl()}')
                        ext.stagingProfileId = '$STAGING_PROFILE_ID'
                        ext.username = 'username'
                        ext.password = 'password'
                    }
                }
            }
        """)

        val pluginDir = Files.createDirectories(projectDir.resolve("gradle-plugin"))
        Files.writeString(pluginDir.resolve("build.gradle"), """
            plugins {
                id('maven-publish')
                id('java-gradle-plugin')
            }
            gradlePlugin {
                plugins {
                    foo {
                        id = 'org.example.foo'
                        implementationClass = 'org.example.FooPlugin'
                    }
                }
            }
            group = 'org.example'
            version = '0.0.1'
        """)
        val srcDir = Files.createDirectories(pluginDir.resolve("src/main/java/org/example/"))
        Files.writeString(srcDir.resolve("FooPlugin.java"), """
            import org.gradle.api.*;
            public class FooPlugin implements Plugin<Project> {
                public void apply(Project p) {}
            }
        """)

        stubCreateStagingRepoRequest(server, "/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)
        expectArtifactUploads(server, "/staging/deployByRepositoryId/$STAGED_REPOSITORY_ID")

        val result = runGradleBuild(gradleVersion, projectDir, "publishToNexus")

        assertSuccess(result, ":gradle-plugin:initializeNexusStagingRepository")
        assertUploadedToStagingRepo(server, "/org/example/gradle-plugin/0.0.1/gradle-plugin-0.0.1.pom")
        assertUploadedToStagingRepo(server, "/org/example/foo/org.example.foo.gradle.plugin/0.0.1/org.example.foo.gradle.plugin-0.0.1.pom")
    }

    @ParameterizedTest
    @MethodSource("gradleVersionAndSettings")
    fun `publishes snapshots`(gradleVersion: String, extraSettings: String, @TempDir projectDir: Path, server: WireMockServer) {
        Files.writeString(projectDir.resolve("settings.gradle"), """
            rootProject.name = 'sample'
            $extraSettings
        """)

        Files.writeString(projectDir.resolve("build.gradle"), """
            plugins {
                id('java-library')
                id('de.marcphilipp.nexus-publish')
            }
            group = 'org.example'
            version = '0.0.1-SNAPSHOT'
            publishing {
                publications {
                    mavenJava(MavenPublication) {
                        from(components.java)
                    }
                }
            }
            nexusPublishing {
                serverUrl = uri('${server.baseUrl()}/shouldNotBeUsed')
                snapshotRepositoryUrl = uri('${server.baseUrl()}/snapshots')
                username = 'username'
                password = 'password'
            }
        """)

        expectArtifactUploads(server, "/snapshots")

        val result = runGradleBuild(gradleVersion, projectDir, "publishToNexus")

        assertSkipped(result, ":initializeNexusStagingRepository")
        assertUploaded(server, "/snapshots/org/example/sample/0.0.1-SNAPSHOT/sample-0.0.1-.*.pom")
        assertUploaded(server, "/snapshots/org/example/sample/0.0.1-SNAPSHOT/sample-0.0.1-.*.jar")
    }

    @ParameterizedTest
    @MethodSource("gradleVersionAndSettings")
    fun `creates single staging repository per server url`(gradleVersion: String, extraSettings: String, @TempDir projectDir: Path, server: WireMockServer) {
        Files.writeString(projectDir.resolve("settings.gradle"), """
            rootProject.name = 'sample'
            $extraSettings
            include 'a1', 'a2', 'b'
        """)

        Files.writeString(projectDir.resolve("build.gradle"), """
            plugins {
                id('de.marcphilipp.nexus-publish') apply false
            }
            subprojects {
                apply plugin: 'java-library'
                apply plugin: 'de.marcphilipp.nexus-publish'
                group = 'org.example'
                version = '0.0.1'
                publishing {
                    publications {
                        mavenJava(MavenPublication) {
                            from(components.java)
                        }
                    }
                }
                nexusPublishing {
                    serverUrl = uri('${server.baseUrl()}/a/')
                    stagingProfileId = 'profile-a'
                    username = 'username'
                    password = 'password'
                }
            }
            project(':b:') {
                nexusPublishing {
                    serverUrl = uri('${server.baseUrl()}/b/')
                    stagingProfileId = 'profile-b'
                }
            }
        """)

        stubCreateStagingRepoRequest(server, "/a/staging/profiles/profile-a/start", "orgexample-a")
        expectArtifactUploads(server, "/a/staging/deployByRepositoryId/orgexample-a")
        stubCreateStagingRepoRequest(server, "/b/staging/profiles/profile-b/start", "orgexample-b")
        expectArtifactUploads(server, "/b/staging/deployByRepositoryId/orgexample-b")

        val result = runGradleBuild(gradleVersion, projectDir, "publishToNexus", "--parallel")

        server.verify(1, postRequestedFor(urlEqualTo("/a/staging/profiles/profile-a/start")))
        server.verify(1, postRequestedFor(urlEqualTo("/b/staging/profiles/profile-b/start")))
        assertSuccess(result, ":a1:initializeNexusStagingRepository")
        assertSuccess(result, ":a2:initializeNexusStagingRepository")
        assertSuccess(result, ":b:initializeNexusStagingRepository")
        assertUploaded(server, "/a/staging/deployByRepositoryId/orgexample-a/org/example/a1/0.0.1/a1-0.0.1.pom")
        assertUploaded(server, "/a/staging/deployByRepositoryId/orgexample-a/org/example/a2/0.0.1/a2-0.0.1.pom")
        assertUploaded(server, "/b/staging/deployByRepositoryId/orgexample-b/org/example/b/0.0.1/b-0.0.1.jar")
    }

    @ParameterizedTest
    @MethodSource("gradleVersionAndSettings")
    fun `configures staging repository id in staging plugin`(gradleVersion: String, extraSettings: String, @TempDir projectDir: Path, server: WireMockServer) {
        Files.writeString(projectDir.resolve("settings.gradle"), """
            rootProject.name = 'sample'
            $extraSettings
        """)
        Files.writeString(projectDir.resolve("build.gradle"), """
            buildscript {
                repositories {
                    gradlePluginPortal()
                }
                dependencies {
                    classpath "io.codearte.gradle.nexus:gradle-nexus-staging-plugin:0.20.0"
                    classpath files($pluginClasspathAsString)
                }
            }
            plugins {
                id('java-library')
            }
            apply plugin: 'io.codearte.nexus-staging'
            apply plugin: 'de.marcphilipp.nexus-publish'
            group = 'org.example'
            version = '0.0.1'
            publishing {
                publications {
                    mavenJava(MavenPublication) {
                        from(components.java)
                    }
                }
            }
            nexusPublishing {
                serverUrl = uri('${server.baseUrl()}')
            }
            nexusStaging {
                serverUrl = uri('${server.baseUrl()}')
                stagingProfileId = '$STAGING_PROFILE_ID'
            }
        """)

        stubCreateStagingRepoRequest(server, "/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)
        server.stubFor(post(urlEqualTo("/staging/bulk/close"))
                .withRequestBody(matchingJsonPath("\$.data[?(@.stagedRepositoryIds[0] == '$STAGED_REPOSITORY_ID')]"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")))
        server.stubFor(get(urlEqualTo("/staging/repository/$STAGED_REPOSITORY_ID"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"transitioning\":false,\"type\":\"CLOSED\"}")))

        val result = runGradleBuild(gradleVersion, projectDir, "initializeNexusStagingRepository", "closeRepository")

        assertSuccess(result, ":initializeNexusStagingRepository")
        assertSuccess(result, ":closeRepository")
        assertCloseOfStagingRepo(server)
    }

    @ParameterizedTest
    @MethodSource("gradleVersionAndSettings")
    fun `warns about too old staging plugin`(gradleVersion: String, extraSettings: String, @TempDir projectDir: Path, server: WireMockServer) {
        Files.writeString(projectDir.resolve("settings.gradle"), """
            rootProject.name = 'sample'
            $extraSettings
        """)
        Files.writeString(projectDir.resolve("build.gradle"), """
            buildscript {
                repositories {
                    gradlePluginPortal()
                }
                dependencies {
                    classpath "io.codearte.gradle.nexus:gradle-nexus-staging-plugin:0.12.0"
                    classpath files($pluginClasspathAsString)
                }
            }
            plugins {
                id('java-library')
            }
            apply plugin: 'io.codearte.nexus-staging'
            apply plugin: 'de.marcphilipp.nexus-publish'
            group = 'org.example'
            version = '0.0.1'
            publishing {
                publications {
                    mavenJava(MavenPublication) {
                        from(components.java)
                    }
                }
            }
            nexusPublishing {
                serverUrl = uri('${server.baseUrl()}')
            }
            nexusStaging {
                stagingProfileId = '$STAGING_PROFILE_ID'
            }
        """)

        stubCreateStagingRepoRequest(server, "/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)

        val result = runGradleBuild(gradleVersion, projectDir, "initializeNexusStagingRepository")

        assertSuccess(result, ":initializeNexusStagingRepository")
        assertThat(result.output).contains("at least 0.20.0")
    }

    private fun runGradleBuild(gradleVersion: String, projectDir: Path, vararg arguments: String): BuildResult {
        return gradleRunner
                .withGradleVersion(gradleVersion)
                .withProjectDir(projectDir.toFile())
                .withArguments(*arguments)
                .forwardOutput()
                .build()
    }

    @SafeVarargs
    private fun stubStagingProfileRequest(server: WireMockServer, url: String, vararg stagingProfiles: Map<String, String>) {
        server.stubFor(get(urlEqualTo(url))
                .willReturn(aResponse().withBody(gson.toJson(mapOf("data" to listOf(*stagingProfiles))))))
    }

    private fun stubCreateStagingRepoRequest(server: WireMockServer, url: String, stagedRepositoryId: String) {
        server.stubFor(post(urlEqualTo(url))
                .willReturn(aResponse().withBody(gson.toJson(mapOf("data" to mapOf("stagedRepositoryId" to stagedRepositoryId))))))
    }

    private fun expectArtifactUploads(server: WireMockServer, prefix: String) {
        server.stubFor(put(urlMatching("$prefix/.+"))
                .willReturn(aResponse().withStatus(201)))
        server.stubFor(get(urlMatching("$prefix/.+/maven-metadata.xml"))
                .willReturn(aResponse().withStatus(404)))
    }

    private fun assertSuccess(result: BuildResult, taskPath: String) {
        assertOutcome(result, taskPath, SUCCESS)
    }

    private fun assertSkipped(result: BuildResult, taskPath: String) {
        assertOutcome(result, taskPath, SKIPPED)
    }

    private fun assertOutcome(result: BuildResult, taskPath: String, outcome: TaskOutcome) {
        assertThat(result.task(taskPath)).describedAs("Task $taskPath")
                .isNotNull
                .extracting { it.outcome }
                .isEqualTo(outcome)
    }

    private fun assertNotConsidered(result: BuildResult, taskPath: String) {
        assertThat(result.task(taskPath)).describedAs("Task $taskPath").isNull()
    }

    private fun assertUploadedToStagingRepo(server: WireMockServer, path: String) {
        assertUploaded(server, "/staging/deployByRepositoryId/$STAGED_REPOSITORY_ID$path")
    }

    private fun assertUploaded(server: WireMockServer, testUrl: String) {
        server.verify(putRequestedFor(urlMatching(testUrl)))
    }

    private fun assertCloseOfStagingRepo(server: WireMockServer) {
        server.verify(postRequestedFor(urlMatching("/staging/bulk/close"))
                .withRequestBody(matchingJsonPath("\$.data[?(@.stagedRepositoryIds[0] == '$STAGED_REPOSITORY_ID')]")))
    }

}
