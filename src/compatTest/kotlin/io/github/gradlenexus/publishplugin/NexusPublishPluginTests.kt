/*
 * Copyright 2020 the original author or authors.
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
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.google.gson.Gson
import io.github.gradlenexus.publishplugin.internal.StagingRepository
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
        private const val OVERRIDDEN_STAGED_REPOSITORY_ID = "orgexample-42o"
    }

    private enum class StagingRepoTransitionOperation(val urlSufix: String, val desiredState: StagingRepository.State) {
        CLOSE("close", StagingRepository.State.CLOSED), RELEASE("promote", StagingRepository.State.NOT_FOUND)
    }

    private val gson = Gson()

    private val gradleVersion = System.getProperty("compat.gradle.version") ?: GradleVersion.current().version

    private val gradleRunner = GradleRunner.create()
            .withPluginClasspath()
            .withGradleVersion(gradleVersion)

    private val pluginClasspathAsString: String
        get() = gradleRunner.pluginClasspath.joinToString(", ") { "'${it.absolutePath.replace('\\', '/')}'" }

    lateinit var server: WireMockServer

    @TempDir
    lateinit var projectDir: Path

    lateinit var buildGradle: Path

    @BeforeEach
    internal fun setup(@Wiremock server: WireMockServer) {
        this.server = server
        buildGradle = projectDir.resolve("build.gradle")
    }

    @Test
    fun `must be applied to root project`() {
        projectDir.resolve("settings.gradle").append("""
            include('sub')
        """)
        buildGradle.append("""
            plugins {
                id('io.github.gradle-nexus.publish-plugin') apply false
            }
            subprojects {
                apply plugin: 'io.github.gradle-nexus.publish-plugin'
            }
        """)

        val result = gradleRunner("tasks").buildAndFail()

        assertThat(result.output).contains("Plugin must be applied to the root project but was applied to :sub")
    }

    @Test
    fun `publish task depends on correct tasks`() {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
        """)
        projectDir.resolve("build.gradle").write("""
            plugins {
                id('java-library')
                id('maven-publish')
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
    fun `publishes to Nexus`() {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
        """)
        projectDir.resolve("build.gradle").write("""
            plugins {
                id('java-library')
                id('maven-publish')
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

        stubStagingProfileRequest("/staging/profiles", mapOf("id" to STAGING_PROFILE_ID, "name" to "org.example"))
        stubCreateStagingRepoRequest("/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)
        expectArtifactUploads("/staging/deployByRepositoryId/$STAGED_REPOSITORY_ID")

        val result = run("publishToMyNexus")

        assertSuccess(result, ":initializeMyNexusStagingRepository")
        assertThat(result.output).containsOnlyOnce("Created staging repository '$STAGED_REPOSITORY_ID' at ${server.baseUrl()}/repositories/$STAGED_REPOSITORY_ID/content/")
        assertNotConsidered(result, ":initializeSomeOtherNexusStagingRepository")
        server.verify(postRequestedFor(urlEqualTo("/staging/profiles/$STAGING_PROFILE_ID/start"))
                .withRequestBody(matchingJsonPath("\$.data[?(@.description == 'org.example:sample:0.0.1')]")))
        assertUploadedToStagingRepo("/org/example/sample/0.0.1/sample-0.0.1.pom")
        assertUploadedToStagingRepo("/org/example/sample/0.0.1/sample-0.0.1.jar")
    }

    @Test
    fun `can be used with lazily applied Gradle Plugin Development Plugin`() {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
            include 'gradle-plugin'
        """)
        if (GradleVersion.version(gradleVersion) < GradleVersion.version("5.0")) {
            projectDir.resolve("settings.gradle").append("""
                enableFeaturePreview("STABLE_PUBLISHING")
            """)
        }

        projectDir.resolve("build.gradle").write("""
            plugins {
                id('io.github.gradle-nexus.publish-plugin')
            }
            nexusPublishing {
                repositories {
                    sonatype {
                        nexusUrl = uri('${server.baseUrl()}')
                        stagingProfileId = '$STAGING_PROFILE_ID'
                        username = 'username'
                        password = 'password'
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

        stubCreateStagingRepoRequest("/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)
        expectArtifactUploads("/staging/deployByRepositoryId/$STAGED_REPOSITORY_ID")

        val result = run("publishToSonatype", "-s")

        assertSuccess(result, ":initializeSonatypeStagingRepository")
        assertUploadedToStagingRepo("/org/example/gradle-plugin/0.0.1/gradle-plugin-0.0.1.pom")
        assertUploadedToStagingRepo("/org/example/foo/org.example.foo.gradle.plugin/0.0.1/org.example.foo.gradle.plugin-0.0.1.pom")
    }

    @Test
    fun `publishes snapshots`() {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
        """)

        projectDir.resolve("build.gradle").write("""
            plugins {
                id('java-library')
                id('maven-publish')
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

        expectArtifactUploads("/snapshots")

        val result = run("publishToMyNexus")

        assertSkipped(result, ":initializeMyNexusStagingRepository")
        assertUploaded("/snapshots/org/example/sample/0.0.1-SNAPSHOT/sample-0.0.1-.*.pom")
        assertUploaded("/snapshots/org/example/sample/0.0.1-SNAPSHOT/sample-0.0.1-.*.jar")
    }

    @Test
    fun `uses configured timeout`() {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
        """)
        projectDir.resolve("build.gradle").write("""
            import java.time.Duration

            plugins {
                id('java-library')
                id('maven-publish')
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
    @Disabled("Fails on my Fedora...")
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
                id('maven-publish')
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
                        snapshotRepositoryUrl = uri('$nonRoutableAddress/snapshots/')
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
        """)
        projectDir.resolve("build.gradle").write("""
            plugins {
                id('io.github.gradle-nexus.publish-plugin')
            }
            task printSonatypeConfig {
                doFirst {
                    println "nexusUrl = ${"$"}{nexusPublishing.repositories['sonatype'].nexusUrl.orNull}"
                    println "snapshotRepositoryUrl = ${"$"}{nexusPublishing.repositories['sonatype'].snapshotRepositoryUrl.orNull}"
                }
            }
            nexusPublishing {
                repositories {
                    sonatype()
                }
            }
        """)

        val result = run("printSonatypeConfig")

        assertThat(result.output)
                .contains("nexusUrl = https://oss.sonatype.org/service/local/")
                .contains("snapshotRepositoryUrl = https://oss.sonatype.org/content/repositories/snapshots/")
    }

    @Test
    fun `uses default URLs for sonatype repos in Kotlin DSL`() {
        projectDir.resolve("settings.gradle").write("""
            rootProject.name = 'sample'
        """)
        projectDir.resolve("build.gradle.kts").write("""
            plugins {
                id("io.github.gradle-nexus.publish-plugin")
            }
            tasks.create("printSonatypeConfig") {
                doFirst {
                    println("nexusUrl = ${"$"}{nexusPublishing.repositories["sonatype"].nexusUrl.orNull}")
                    println("snapshotRepositoryUrl = ${"$"}{nexusPublishing.repositories["sonatype"].snapshotRepositoryUrl.orNull}")
                }
            }
            nexusPublishing {
                repositories {
                    sonatype()
                }
            }
        """)

        val result = run("printSonatypeConfig")

        assertThat(result.output)
                .contains("nexusUrl = https://oss.sonatype.org/service/local/")
                .contains("snapshotRepositoryUrl = https://oss.sonatype.org/content/repositories/snapshots/")
    }

    @Test
    fun `should close staging repository`() {
        writeDefaultSingleProjectConfiguration()
        writeMockedSonatypeNexusPublishingConfiguration()

        stubCreateStagingRepoRequest("/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)
        stubCloseStagingRepoRequestWithSubsequentQueryAboutItsState(STAGED_REPOSITORY_ID)

        val result = run("initializeSonatypeStagingRepository", "closeSonatypeStagingRepository")

        assertSuccess(result, ":initializeSonatypeStagingRepository")
        assertSuccess(result, ":closeSonatypeStagingRepository")
        assertCloseOfStagingRepo()
    }

    private fun stubCloseStagingRepoRequestWithSubsequentQueryAboutItsState(stagingRepositoryId: String = STAGED_REPOSITORY_ID) {
        stubTransitToDesiredStateStagingRepoRequestWithSubsequentQueryAboutItsState(StagingRepoTransitionOperation.CLOSE, stagingRepositoryId)
    }

    private fun stubReleaseStagingRepoRequestWithSubsequentQueryAboutItsState(stagingRepositoryId: String = STAGED_REPOSITORY_ID) {
        stubTransitToDesiredStateStagingRepoRequestWithSubsequentQueryAboutItsState(StagingRepoTransitionOperation.RELEASE, stagingRepositoryId)
    }

    private fun stubTransitToDesiredStateStagingRepoRequestWithSubsequentQueryAboutItsState(
        operation: StagingRepoTransitionOperation,
        stagingRepositoryId: String
    ) {
        stubTransitToDesiredStateStagingRepoRequest(operation, stagingRepositoryId)
        stubGetStagingRepoWithIdAndStateRequest(StagingRepository(stagingRepositoryId,
                operation.desiredState, false))
    }

    private fun stubTransitToDesiredStateStagingRepoRequest(
        operation: StagingRepoTransitionOperation,
        stagingRepositoryId: String = STAGED_REPOSITORY_ID
    ) {
        server.stubFor(post(urlEqualTo("/staging/bulk/${operation.urlSufix}"))
                .withRequestBody(matchingJsonPath("\$.data[?(@.stagedRepositoryIds[0] == '$stagingRepositoryId')]"))
                .withRequestBody(matchingJsonPath("\$.data[?(@.autoDropAfterRelease == true)]"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")))
    }

    @Test
    fun `should close and release staging repository`() {
        writeDefaultSingleProjectConfiguration()
        writeMockedSonatypeNexusPublishingConfiguration()

        stubCreateStagingRepoRequest("/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)
        stubReleaseStagingRepoRequestWithSubsequentQueryAboutItsState(STAGED_REPOSITORY_ID)

        val result = run("tasks", "initializeSonatypeStagingRepository", "releaseSonatypeStagingRepository")

        assertSuccess(result, ":initializeSonatypeStagingRepository")
        assertSuccess(result, ":releaseSonatypeStagingRepository")
        assertReleaseOfStagingRepo()
    }

    //TODO: Move to separate subclass with command line tests for @Option
    //TODO: Consider switching to parameterized tests for close and release
    @Test
    fun `should allow to take staging repo id to close from command line without its initialization`() {
        writeDefaultSingleProjectConfiguration()
        writeMockedSonatypeNexusPublishingConfiguration()
        //and
        stubCloseStagingRepoRequestWithSubsequentQueryAboutItsState(OVERRIDDEN_STAGED_REPOSITORY_ID)

        val result = run("closeSonatypeStagingRepository", "--staging-repository-id=$OVERRIDDEN_STAGED_REPOSITORY_ID")

        assertSuccess(result, ":closeSonatypeStagingRepository")
        assertCloseOfStagingRepo(OVERRIDDEN_STAGED_REPOSITORY_ID)
    }

    @Test
    fun `should allow to take staging repo id to release from command line without its initialization`() {
        writeDefaultSingleProjectConfiguration()
        writeMockedSonatypeNexusPublishingConfiguration()
        //and
        stubReleaseStagingRepoRequestWithSubsequentQueryAboutItsState(OVERRIDDEN_STAGED_REPOSITORY_ID)

        val result = run("releaseSonatypeStagingRepository", "--staging-repository-id=$OVERRIDDEN_STAGED_REPOSITORY_ID")

        assertSuccess(result, ":releaseSonatypeStagingRepository")
        assertReleaseOfStagingRepo(OVERRIDDEN_STAGED_REPOSITORY_ID)
    }

    @Test
    @Disabled("Should override or fail with meaningful error?")
    fun `command line option should override initialized staging repository to close`() {
    }

    @Test
    @Disabled("Should override or fail with meaningful error?")
    fun `command line option should override initialized staging repository to release`() {
    }

    @Test
    internal fun `initialize task should resolve stagingProfileId if not provided and keep it for close task`() {
        writeDefaultSingleProjectConfiguration()
        //and
        buildGradle.append("""
            nexusPublishing {
                repositories {
                    sonatype {
                        nexusUrl = uri('${server.baseUrl()}')
                        //No staging profile defined
                    }
                }
            }
        """)
        //and
        stubGetStagingProfilesForOneProfileIdGivenId(STAGING_PROFILE_ID)
        stubCreateStagingRepoRequest("/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)
        stubCloseStagingRepoRequestWithSubsequentQueryAboutItsState()

        val result = run("initializeSonatypeStagingRepository", "closeSonatypeStagingRepository")

        assertSuccess(result, ":initializeSonatypeStagingRepository")
        assertSuccess(result, ":closeSonatypeStagingRepository")
        //and
        assertGetStagingProfile(1)
    }

    //TODO: Parameterize them
    @Test
    internal fun `close task should retry getting repository state on transitioning`() {
        writeDefaultSingleProjectConfiguration()
        writeMockedSonatypeNexusPublishingConfiguration()
        //and
        stubTransitToDesiredStateStagingRepoRequest(StagingRepoTransitionOperation.CLOSE)
        stubGetGivenStagingRepositoryInFirstAndSecondCall(StagingRepository(STAGED_REPOSITORY_ID, StagingRepository.State.OPEN, true), StagingRepository(STAGED_REPOSITORY_ID, StagingRepository.State.CLOSED, false))

        val result = run("closeSonatypeStagingRepository", "--staging-repository-id=$STAGED_REPOSITORY_ID")

        assertSuccess(result, ":closeSonatypeStagingRepository")
        //and
        assertGetStagingRepository(STAGED_REPOSITORY_ID, 2)
    }

    @Test
    internal fun `release task should retry getting repository state on transitioning`() {
        writeDefaultSingleProjectConfiguration()
        writeMockedSonatypeNexusPublishingConfiguration()
        //and
        stubTransitToDesiredStateStagingRepoRequest(StagingRepoTransitionOperation.RELEASE)
        stubGetGivenStagingRepositoryInFirstAndSecondCall(StagingRepository(STAGED_REPOSITORY_ID, StagingRepository.State.CLOSED, true), StagingRepository(STAGED_REPOSITORY_ID, StagingRepository.State.NOT_FOUND, false))

        val result = run("releaseSonatypeStagingRepository", "--staging-repository-id=$STAGED_REPOSITORY_ID")

        assertSuccess(result, ":releaseSonatypeStagingRepository")
        //and
        assertGetStagingRepository(STAGED_REPOSITORY_ID, 2)
    }

    @Test
    fun `disables tasks for removed repos`() {
        writeDefaultSingleProjectConfiguration()
        projectDir.resolve("build.gradle").append("""
            nexusPublishing {
                repositories {
                    remove(create("myNexus") {
                        nexusUrl = uri('${server.baseUrl()}/b/')
                        snapshotRepositoryUrl = uri('${server.baseUrl()}/b/snapshots/')
                    })
                }
            }
        """)

        val result = run("initializeMyNexusStagingRepository")

        assertSkipped(result, ":initializeMyNexusStagingRepository")
    }

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
                id('maven-publish')
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

    private fun writeMockedSonatypeNexusPublishingConfiguration() {
        buildGradle.append("""
            nexusPublishing {
                repositories {
                    sonatype {
                        nexusUrl = uri('${server.baseUrl()}')
                        stagingProfileId = '$STAGING_PROFILE_ID'
                        retrying {
                            maxRetries.set(3)
                            delayBetween.set(java.time.Duration.ofMillis(1))
                        }
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
    private fun stubStagingProfileRequest(url: String, vararg stagingProfiles: Map<String, String>) {
        server.stubFor(get(urlEqualTo(url))
                .withHeader("User-Agent", matching("gradle-nexus-publish-plugin/.*"))
                .willReturn(aResponse().withBody(gson.toJson(mapOf("data" to listOf(*stagingProfiles))))))
    }

    private fun stubCreateStagingRepoRequest(url: String, stagedRepositoryId: String) {
        server.stubFor(post(urlEqualTo(url))
                .willReturn(aResponse().withBody(gson.toJson(mapOf("data" to mapOf("stagedRepositoryId" to stagedRepositoryId))))))
    }

    private fun stubGetStagingProfilesForOneProfileIdGivenId(stagingProfileId: String = STAGING_PROFILE_ID) {
        server.stubFor(get(urlEqualTo("/staging/profiles"))
                        .withHeader("Accept", containing("application/json"))
                        .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(getOneStagingProfileWithGivenIdShrunkJsonResponseAsString(stagingProfileId))))
    }

    private fun stubGetStagingRepoWithIdAndStateRequest(stagingRepository: StagingRepository) {
        if (stagingRepository.state == StagingRepository.State.NOT_FOUND) {
            val responseBody = """{"errors":[{"id":"*","msg":"No such repository: ${stagingRepository.id}"}]}"""
            stubGetStagingRepoWithIdAndResponseStatusCodeAndResponseBody(stagingRepository.id, 404, responseBody)
        } else {
            val responseBody = getOneStagingRepoWithGivenIdJsonResponseAsString(stagingRepository)
            stubGetStagingRepoWithIdAndResponseStatusCodeAndResponseBody(stagingRepository.id, 200, responseBody)
        }
    }

    private fun stubGetStagingRepoWithIdAndResponseStatusCodeAndResponseBody(
        stagingRepositoryId: String,
        statusCode: Int,
        responseBody: String
    ) {
        server.stubFor(get(urlEqualTo("/staging/repository/$stagingRepositoryId"))
                .withHeader("Accept", containing("application/json"))
                .willReturn(aResponse()
                        .withStatus(statusCode)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)
                ))
    }

    private fun stubGetGivenStagingRepositoryInFirstAndSecondCall(stagingRepository1: StagingRepository, stagingRepository2: StagingRepository) {
        server.stubFor(get(urlEqualTo("/staging/repository/${stagingRepository1.id}"))
                .inScenario("State")
                .whenScenarioStateIs(Scenario.STARTED)
                .withHeader("Accept", containing("application/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(getOneStagingRepoWithGivenIdJsonResponseAsString(stagingRepository1)))
                .willSetStateTo("CLOSED"))

        server.stubFor(get(urlEqualTo("/staging/repository/${stagingRepository2.id}"))
                .inScenario("State")
                .whenScenarioStateIs("CLOSED")
                .withHeader("Accept", containing("application/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(getOneStagingRepoWithGivenIdJsonResponseAsString(stagingRepository2))))
    }

    private fun expectArtifactUploads(prefix: String) {
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
                .extracting { it!!.outcome }
                .isEqualTo(outcome)
    }

    private fun assertNotConsidered(result: BuildResult, taskPath: String) {
        assertThat(result.task(taskPath)).describedAs("Task $taskPath").isNull()
    }

    private fun assertGetStagingProfile(count: Int = 1) {
        server.verify(count, getRequestedFor(urlMatching("/staging/profiles")))
    }

    private fun assertUploadedToStagingRepo(path: String) {
        assertUploaded("/staging/deployByRepositoryId/$STAGED_REPOSITORY_ID$path")
    }

    private fun assertUploaded(testUrl: String) {
        server.verify(putRequestedFor(urlMatching(testUrl)))
    }

    private fun assertCloseOfStagingRepo(stagingRepositoryId: String = STAGED_REPOSITORY_ID) {
        assertGivenTransitionOperationOfStagingRepo("close", stagingRepositoryId)
    }

    private fun assertReleaseOfStagingRepo(stagingRepositoryId: String = STAGED_REPOSITORY_ID) {
        assertGivenTransitionOperationOfStagingRepo("promote", stagingRepositoryId)
    }

    private fun assertGivenTransitionOperationOfStagingRepo(transitionOperation: String, stagingRepositoryId: String) {
        server.verify(postRequestedFor(urlMatching("/staging/bulk/$transitionOperation"))
                .withRequestBody(matchingJsonPath("\$.data[?(@.stagedRepositoryIds[0] == '$stagingRepositoryId')]")))
    }

    private fun assertGetStagingRepository(stagingRepositoryId: String = STAGED_REPOSITORY_ID, count: Int = 1) {
        server.verify(count, getRequestedFor(urlMatching("/staging/repository/$stagingRepositoryId")))
    }

    private fun getOneStagingProfileWithGivenIdShrunkJsonResponseAsString(stagingProfileId: String): String {
        return """
            {
              "data": [
                {
                  "deployURI": "https://oss.sonatype.org/service/local/staging/deploy/maven2",
                  "id": "$stagingProfileId",
                  "inProgress": false,
                  "mode": "BOTH",
                  "name": "org.example",
                  "order": 6445,
                  "promotionTargetRepository": "releases",
                  "repositoryType": "maven2",
                  "resourceURI": "https://oss.sonatype.org/service/local/staging/profiles/$stagingProfileId",
                  "targetGroups": ["staging"]
                }
              ]
            }
        """.trimIndent()
    }

    private fun getOneStagingRepoWithGivenIdJsonResponseAsString(
        stagingRepository: StagingRepository,
        stagingProfileId: String = STAGING_PROFILE_ID
    ): String {
        return """
            {
              "profileId": "$stagingProfileId",
              "profileName": "some.profile.id",
              "profileType": "repository",
              "repositoryId": "${stagingRepository.id}",
              "type": "${stagingRepository.state}",
              "policy": "release",
              "userId": "gradle-nexus-e2e",
              "userAgent": "okhttp/3.14.4",
              "ipAddress": "1.1.1.1",
              "repositoryURI": "https://oss.sonatype.org/content/repositories/${stagingRepository.id}",
              "created": "2020-01-28T09:51:42.804Z",
              "createdDate": "Tue Jan 28 09:51:42 UTC 2020",
              "createdTimestamp": 1580205102804,
              "updated": "2020-01-28T10:23:49.616Z",
              "updatedDate": "Tue Jan 28 10:23:49 UTC 2020",
              "updatedTimestamp": 1580207029616,
              "description": "Closed by io.github.gradle-nexus.publish-plugin Gradle plugin",
              "provider": "maven2",
              "releaseRepositoryId": "no-sync-releases",
              "releaseRepositoryName": "No Sync Releases",
              "notifications": 0,
              "transitioning": ${stagingRepository.transitioning}
            }
        """.trimIndent()
    }
}
