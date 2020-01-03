/*
 * Copyright 2019 the original author or authors.
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

package io.github.gradlenexus.publishplugin

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
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
import org.gradle.testkit.runner.TaskOutcome.FAILED
import org.gradle.testkit.runner.TaskOutcome.SKIPPED
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.util.GradleVersion
import org.gradle.util.VersionNumber
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import ru.lanwen.wiremock.ext.WiremockResolver
import ru.lanwen.wiremock.ext.WiremockResolver.Wiremock
import java.nio.file.Files
import java.nio.file.Path

@Suppress("FunctionName") // TODO: How to suppress "kotlin:S100" from SonarLint?
@ExtendWith(WiremockResolver::class)
class NexusPublishPluginTests {

    companion object {
        private const val STAGING_PROFILE_ID = "someProfileId"
        private const val STAGED_REPOSITORY_ID = "orgexample-42"
    }

    private val gson = Gson()

    private val gradleVersion = System.getProperty("compat.gradle.version") ?: GradleVersion.current().version

    private val gradleRunner = GradleRunner.create()
            .withPluginClasspath()
            .withGradleVersion(gradleVersion)

    private val pluginClasspathAsString: String
        get() = gradleRunner.pluginClasspath.joinToString(", ") { "'${it.absolutePath.replace('\\', '/')}'" }

    @TempDir
    lateinit var projectDir: Path

    lateinit var buildGradle: Path

    @BeforeEach
    internal fun setUp() {
        buildGradle = projectDir.resolve("build.gradle")
    }

    @Test
    fun `publish task depends on correct tasks`() {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
        """)
        projectDir.resolve("build.gradle").write("""
            plugins {
                id('java-library')
                id('io.github.gradle-nexus.publish-plugin')
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
            nexusPublishing {
                repositories {
                    myNexus {
                        nexusUrl = uri('https://example.com')
                    }
                }
            }
            // use this instead of --dry-run to get the tasks in the result for verification
            tasks.all { enabled = false }
        """)

        val result = run("publishToMyNexus")

        assertSkipped(result, ":publishToMyNexus")
        assertSkipped(result, ":publishMavenJavaPublicationToMyNexusRepository")
        assertNotConsidered(result, ":publishMavenJavaPublicationToSomeOtherRepoRepository")
    }

    @Test
    fun `publishes to Nexus`(@Wiremock server: WireMockServer) {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
        """)
        projectDir.resolve("build.gradle").write("""
            plugins {
                id('java-library')
                id('io.github.gradle-nexus.publish-plugin')
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
                repositories {
                    myNexus {
                        nexusUrl = uri('${server.baseUrl()}')
                        snapshotRepositoryUrl = uri('${server.baseUrl()}/snapshots/')
                        username = 'username'
                        password = 'password'
                    }
                    someOtherNexus {
                        nexusUrl = uri('http://example.org')
                        snapshotRepositoryUrl = uri('http://example.org/snapshots/')
                    }
                }
            }
        """)

        stubStagingProfileRequest(server, "/staging/profiles", mapOf("id" to STAGING_PROFILE_ID, "name" to "org.example"))
        stubCreateStagingRepoRequest(server, "/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)
        expectArtifactUploads(server, "/staging/deployByRepositoryId/$STAGED_REPOSITORY_ID")

        val result = run("publishToMyNexus")

        assertSuccess(result, ":initializeMyNexusStagingRepository")
        assertNotConsidered(result, ":initializeSomeOtherNexusStagingRepository")
        server.verify(postRequestedFor(urlEqualTo("/staging/profiles/$STAGING_PROFILE_ID/start"))
                .withRequestBody(matchingJsonPath("\$.data[?(@.description == 'Created by io.github.gradle-nexus.publish-plugin Gradle plugin')]")))
        assertUploadedToStagingRepo(server, "/org/example/sample/0.0.1/sample-0.0.1.pom")
        assertUploadedToStagingRepo(server, "/org/example/sample/0.0.1/sample-0.0.1.jar")
    }

    @Test
    fun `can be used with lazily applied Gradle Plugin Development Plugin`(@Wiremock server: WireMockServer) {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
            include 'gradle-plugin'
        """)

        projectDir.resolve("build.gradle").write("""
            buildscript {
                dependencies {
                    classpath files($pluginClasspathAsString)
                }
            }
            allprojects {
                plugins.withId('maven-publish') {
                    project.apply plugin: 'io.github.gradle-nexus.publish-plugin'
                    project.extensions.configure('nexusPublishing') { ext ->
                        ext.repositories {
                            sonatype {
                                nexusUrl = uri('${server.baseUrl()}')
                                stagingProfileId = '$STAGING_PROFILE_ID'
                                username = 'username'
                                password = 'password'
                            }
                        }
                    }
                }
            }
        """)

        val pluginDir = Files.createDirectories(projectDir.resolve("gradle-plugin"))
        pluginDir.resolve("build.gradle").write("""
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
        srcDir.resolve("FooPlugin.java").write("""
            import org.gradle.api.*;
            public class FooPlugin implements Plugin<Project> {
                public void apply(Project p) {}
            }
        """)

        stubCreateStagingRepoRequest(server, "/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)
        expectArtifactUploads(server, "/staging/deployByRepositoryId/$STAGED_REPOSITORY_ID")

        val result = run("publishToSonatype", "-s")

        assertSuccess(result, ":gradle-plugin:initializeSonatypeStagingRepository")
        assertUploadedToStagingRepo(server, "/org/example/gradle-plugin/0.0.1/gradle-plugin-0.0.1.pom")
        assertUploadedToStagingRepo(server, "/org/example/foo/org.example.foo.gradle.plugin/0.0.1/org.example.foo.gradle.plugin-0.0.1.pom")
    }

    @Test
    fun `publishes snapshots`(@Wiremock server: WireMockServer) {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
        """)

        projectDir.resolve("build.gradle").write("""
            plugins {
                id('java-library')
                id('io.github.gradle-nexus.publish-plugin')
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
                repositories {
                    myNexus {
                        nexusUrl = uri('${server.baseUrl()}/shouldNotBeUsed')
                        snapshotRepositoryUrl = uri('${server.baseUrl()}/snapshots')
                        username = 'username'
                        password = 'password'
                    }
                }
            }
        """)

        expectArtifactUploads(server, "/snapshots")

        val result = run("publishToMyNexus")

        assertSkipped(result, ":initializeMyNexusStagingRepository")
        assertUploaded(server, "/snapshots/org/example/sample/0.0.1-SNAPSHOT/sample-0.0.1-.*.pom")
        assertUploaded(server, "/snapshots/org/example/sample/0.0.1-SNAPSHOT/sample-0.0.1-.*.jar")
    }

    @Test
    fun `creates single staging repository per server url`(@Wiremock server: WireMockServer) {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
            include 'a1', 'a2', 'b'
        """)

        projectDir.resolve("build.gradle").write("""
            plugins {
                id('io.github.gradle-nexus.publish-plugin') apply false
            }
            subprojects {
                apply plugin: 'java-library'
                apply plugin: 'io.github.gradle-nexus.publish-plugin'
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
                    repositories {
                        myNexus {
                            nexusUrl = uri('${server.baseUrl()}/a/')
                            snapshotRepositoryUrl = uri('${server.baseUrl()}/a/snapshots/')
                            stagingProfileId = 'profile-a'
                            username = 'username'
                            password = 'password'
                        }
                    }
                }
            }
            project(':b:') {
                nexusPublishing {
                    repositories {
                        named('myNexus').configure {
                            nexusUrl = uri('${server.baseUrl()}/b/')
                            snapshotRepositoryUrl = uri('${server.baseUrl()}/b/snapshots/')
                            stagingProfileId = 'profile-b'
                        }
                    }
                }
            }
        """)

        stubCreateStagingRepoRequest(server, "/a/staging/profiles/profile-a/start", "orgexample-a")
        expectArtifactUploads(server, "/a/staging/deployByRepositoryId/orgexample-a")
        stubCreateStagingRepoRequest(server, "/b/staging/profiles/profile-b/start", "orgexample-b")
        expectArtifactUploads(server, "/b/staging/deployByRepositoryId/orgexample-b")

        val result = run("publishToMyNexus", "--parallel")

        server.verify(1, postRequestedFor(urlEqualTo("/a/staging/profiles/profile-a/start")))
        server.verify(1, postRequestedFor(urlEqualTo("/b/staging/profiles/profile-b/start")))
        assertSuccess(result, ":a1:initializeMyNexusStagingRepository")
        assertSuccess(result, ":a2:initializeMyNexusStagingRepository")
        assertSuccess(result, ":b:initializeMyNexusStagingRepository")
        assertUploaded(server, "/a/staging/deployByRepositoryId/orgexample-a/org/example/a1/0.0.1/a1-0.0.1.pom")
        assertUploaded(server, "/a/staging/deployByRepositoryId/orgexample-a/org/example/a2/0.0.1/a2-0.0.1.pom")
        assertUploaded(server, "/b/staging/deployByRepositoryId/orgexample-b/org/example/b/0.0.1/b-0.0.1.jar")
    }

    @Test
    fun `configures staging repository id in staging plugin`(@Wiremock server: WireMockServer) {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
        """)
        projectDir.resolve("build.gradle").write("""
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
            apply plugin: 'io.github.gradle-nexus.publish-plugin'
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
                repositories {
                    sonatype {
                        nexusUrl = uri('${server.baseUrl()}')
                    }
                }
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

        val result = run("initializeSonatypeStagingRepository", "closeRepository")

        assertSuccess(result, ":initializeSonatypeStagingRepository")
        assertSuccess(result, ":closeRepository")
        assertCloseOfStagingRepo(server)
    }

    @Test
    fun `warns about too old staging plugin`(@Wiremock server: WireMockServer) {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
        """)
        projectDir.resolve("build.gradle").write("""
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
            apply plugin: 'io.github.gradle-nexus.publish-plugin'
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
                repositories {
                    myNexus {
                        nexusUrl = uri('${server.baseUrl()}')
                        snapshotRepositoryUrl = uri('${server.baseUrl()}/snapshots/')
                    }
                }
            }
            nexusStaging {
                stagingProfileId = '$STAGING_PROFILE_ID'
            }
        """)

        stubCreateStagingRepoRequest(server, "/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)

        val result = run("initializeMyNexusStagingRepository")

        assertSuccess(result, ":initializeMyNexusStagingRepository")
        assertThat(result.output).contains("at least 0.20.0")
    }

    @Test
    fun `uses configured timeout`(@Wiremock server: WireMockServer) {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
        """)
        projectDir.resolve("build.gradle").write("""
            import java.time.Duration
            
            plugins {
                id('java-library')
                id('io.github.gradle-nexus.publish-plugin')
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
                repositories {
                    myNexus {
                        nexusUrl = uri('${server.baseUrl()}')
                        snapshotRepositoryUrl = uri('${server.baseUrl()}/snapshots/')
                        username = 'username'
                        password = 'password'
                    }
                }
                clientTimeout = Duration.ofSeconds(1)
            }
        """)

        server.stubFor(get(anyUrl()).willReturn(aResponse().withFixedDelay(5_000)))

        val result = gradleRunner("initializeMyNexusStagingRepository").buildAndFail()

        assertOutcome(result, ":initializeMyNexusStagingRepository", FAILED)
        assertThat(result.output).contains("SocketTimeoutException")
    }

    @Test
    fun `uses configured connect timeout`() {
        assumeTrue(VersionNumber.parse(gradleVersion) >= VersionNumber.parse("5.0"),
                "Task timeouts were added in Gradle 5.0")

        // Taken from https://stackoverflow.com/a/904609/5866817
        val nonRoutableAddress = "10.255.255.1"

        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
        """)
        projectDir.resolve("build.gradle").write("""
            import java.time.Duration
            
            plugins {
                id('java-library')
                id('io.github.gradle-nexus.publish-plugin')
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
                repositories {
                    myNexus {
                        nexusUrl = uri('http://$nonRoutableAddress/')
                        username = 'username'
                        password = 'password'
                    }
                }
                connectTimeout = Duration.ofSeconds(1)
            }
            initializeMyNexusStagingRepository {
                timeout = Duration.ofSeconds(10)
            }
        """)

        val result = gradleRunner("initializeMyNexusStagingRepository").buildAndFail()

        assertOutcome(result, ":initializeMyNexusStagingRepository", FAILED)
        assertThat(result.output).contains("SocketTimeoutException")
    }

    @Test
    fun `uses default URLs for sonatype repos in Groovy DSL`() {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
            include('a', 'b')
        """)
        projectDir.resolve("build.gradle").write("""
            plugins {
                id('io.github.gradle-nexus.publish-plugin') apply false
            }
            subprojects {
                apply plugin: 'io.github.gradle-nexus.publish-plugin'
                task printSonatypeConfig {
                    doFirst {
                        println "${"$"}{project.name}.nexusUrl = ${"$"}{nexusPublishing.repositories['sonatype'].nexusUrl.orNull}"
                        println "${"$"}{project.name}.snapshotRepositoryUrl = ${"$"}{nexusPublishing.repositories['sonatype'].snapshotRepositoryUrl.orNull}"
                    }
                }
            }
            project(':a').nexusPublishing {
                repositories {
                    sonatype()
                }
            }
            project(':b').nexusPublishing {
                repositories {
                    sonatype {
                        nexusUrl = uri('https://example.com')
                    }
                }
            }
        """)

        val result = run("printSonatypeConfig")

        assertThat(result.output)
                .contains("a.nexusUrl = https://oss.sonatype.org/service/local/")
                .contains("a.snapshotRepositoryUrl = https://oss.sonatype.org/content/repositories/snapshots/")
                .contains("b.nexusUrl = https://example.com")
                .contains("b.snapshotRepositoryUrl = https://oss.sonatype.org/content/repositories/snapshots/")
    }

    @Test
    fun `uses default URLs for sonatype repos in Kotlin DSL`() {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
            include('a', 'b')
        """)
        projectDir.resolve("a/build.gradle.kts").write("""
            plugins {
                id("io.github.gradle-nexus.publish-plugin")
            }
            nexusPublishing {
                repositories {
                    sonatype()
                }
            }
            tasks.create("printSonatypeConfig") {
                doFirst {
                    println("${"$"}{project.name}.nexusUrl = ${"$"}{nexusPublishing.repositories["sonatype"].nexusUrl.orNull}")
                    println("${"$"}{project.name}.snapshotRepositoryUrl = ${"$"}{nexusPublishing.repositories["sonatype"].snapshotRepositoryUrl.orNull}")
                }
            }
        """)
        projectDir.resolve("b/build.gradle.kts").write("""
            plugins {
                id("io.github.gradle-nexus.publish-plugin")
            }
            nexusPublishing {
                repositories {
                    sonatype {
                        nexusUrl.set(uri("https://example.com"))
                    }
                    create("anotherRepo") {
                        nexusUrl.set(uri("https://example.org"))
                    }
                }
            }
            tasks.create("printSonatypeConfig") {
                doFirst {
                    println("${"$"}{project.name}.nexusUrl = ${"$"}{nexusPublishing.repositories["sonatype"].nexusUrl.orNull}")
                    println("${"$"}{project.name}.snapshotRepositoryUrl = ${"$"}{nexusPublishing.repositories["sonatype"].snapshotRepositoryUrl.orNull}")
                }
            }
        """)

        val result = run("printSonatypeConfig")

        assertThat(result.output)
                .contains("a.nexusUrl = https://oss.sonatype.org/service/local/")
                .contains("a.snapshotRepositoryUrl = https://oss.sonatype.org/content/repositories/snapshots/")
                .contains("b.nexusUrl = https://example.com")
                .contains("b.snapshotRepositoryUrl = https://oss.sonatype.org/content/repositories/snapshots/")
    }

    @Test
    fun `should close staging repository`(@Wiremock server: WireMockServer) {
        writeDefaultSingleProjectConfiguration()
        buildGradle.append("""
            nexusPublishing {
                repositories {
                    sonatype {
                        nexusUrl = uri('${server.baseUrl()}')
                        stagingProfileId = '$STAGING_PROFILE_ID'
                    }
                }
            }
        """)

        stubCreateStagingRepoRequest(server, "/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)
        server.stubFor(post(urlEqualTo("/staging/bulk/close"))
                .withRequestBody(matchingJsonPath("\$.data[?(@.stagedRepositoryIds[0] == '$STAGED_REPOSITORY_ID')]"))
                .withRequestBody(matchingJsonPath("\$.data[?(@.autoDropAfterRelease == true)]"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")))
        server.stubFor(get(urlEqualTo("/staging/repository/$STAGED_REPOSITORY_ID"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"transitioning\":false,\"type\":\"CLOSED\"}")))

        val result = run("initializeSonatypeStagingRepository", "closeSonatypeStagingRepository")

        assertSuccess(result, ":initializeSonatypeStagingRepository")
        assertSuccess(result, ":closeSonatypeStagingRepository")
        assertCloseOfStagingRepo(server)
    }

    @Test
    fun `should close and release staging repository`(@Wiremock server: WireMockServer) {
        writeDefaultSingleProjectConfiguration()
        buildGradle.append("""
            nexusPublishing {
                repositories {
                    sonatype {
                        nexusUrl = uri('${server.baseUrl()}')
                        stagingProfileId = '$STAGING_PROFILE_ID'
                    }
                }
            }
        """)

        stubCreateStagingRepoRequest(server, "/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)
        server.stubFor(post(urlEqualTo("/staging/bulk/promote"))
                .withRequestBody(matchingJsonPath("\$.data[?(@.stagedRepositoryIds[0] == '$STAGED_REPOSITORY_ID')]"))
                .withRequestBody(matchingJsonPath("\$.data[?(@.autoDropAfterRelease == true)]"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")))
        server.stubFor(get(urlEqualTo("/staging/repository/$STAGED_REPOSITORY_ID"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"transitioning\":false,\"type\":\"RELEASED\"}")))

        val result = run("tasks", "initializeSonatypeStagingRepository", "releaseSonatypeStagingRepository")

        assertSuccess(result, ":initializeSonatypeStagingRepository")
        assertSuccess(result, ":releaseSonatypeStagingRepository")
        assertReleaseOfStagingRepo(server)
    }

    @Test
    @Disabled("TODO")
    fun `should allow to override stagingRepositoryId to close from command line`() {}

    @Test
    @Disabled("TODO")
    fun `should allow to override stagingRepositoryId to release from command line`() {}

    // TODO: To be used also in other tests
    private fun writeDefaultSingleProjectConfiguration() {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
        """)
        projectDir.resolve("build.gradle").write("""
            buildscript {
                repositories {
                    gradlePluginPortal()
                }
                dependencies {
                    classpath files($pluginClasspathAsString)
                }
            }
            plugins {
                id('java-library')
            }
            apply plugin: 'io.github.gradle-nexus.publish-plugin'
            group = 'org.example'
            version = '0.0.1'
            publishing {
                publications {
                    mavenJava(MavenPublication) {
                        from(components.java)
                    }
                }
            }
        """)
    }

    private fun run(vararg arguments: String): BuildResult {
        return gradleRunner(*arguments).build()
    }

    private fun gradleRunner(vararg arguments: String): GradleRunner {
        return gradleRunner
//                .withDebug(true)
                .withProjectDir(projectDir.toFile())
                .withArguments(*arguments, "--stacktrace")
                .forwardOutput()
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
        assertGivenTransitionOperationOfStagingRepo(server, "close")
    }

    private fun assertReleaseOfStagingRepo(server: WireMockServer) {
        assertGivenTransitionOperationOfStagingRepo(server, "promote")
    }

    private fun assertGivenTransitionOperationOfStagingRepo(server: WireMockServer, transitionOperation: String) {
        server.verify(postRequestedFor(urlMatching("/staging/bulk/$transitionOperation"))
                .withRequestBody(matchingJsonPath("\$.data[?(@.stagedRepositoryIds[0] == '$STAGED_REPOSITORY_ID')]")))
    }
}
