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

package io.github.gradlenexus.publishplugin

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import org.assertj.core.api.Assertions
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Test
import ru.lanwen.wiremock.ext.WiremockResolver
import java.nio.file.Files

class IvyNexusPublishPluginTests : BaseNexusPublishPluginTests() {
    init {
        publishPluginId = "ivy-publish"
        publishPluginContent = """  ivyCustom(IvyPublication) {
                                        from(components.java)
                                    }"""
        publishGoalPrefix = "publishIvyCustom"
        publicationTypeName = "IVY"
    }

    @Test
    fun `publishes snapshots`() {
        projectDir.resolve("settings.gradle").write(
            """
            rootProject.name = 'sample'
        """
        )

        projectDir.resolve("build.gradle").write(
            """
            plugins {
                id('java-library')
                id('$publishPluginId')
                id('io.github.gradle-nexus.publish-plugin')
            }
            group = 'org.example'
            version = '0.0.1-SNAPSHOT'
            publishing {
                publications {
                     $publishPluginContent
                }
            }
            nexusPublishing {
                publicationType = io.github.gradlenexus.publishplugin.NexusPublishExtension.PublicationType.$publicationTypeName
                repositories {
                    myNexus {
                        nexusUrl = uri('${server.baseUrl()}/shouldNotBeUsed')
                        snapshotRepositoryUrl = uri('${server.baseUrl()}/snapshots')
                        allowInsecureProtocol = true
                        username = 'username'
                        password = 'password'
                    }
                }
            }
            """
        )

        expectArtifactUploads("/snapshots")

        val result = run("publishToMyNexus")

        assertSkipped(result, ":initializeMyNexusStagingRepository")
        assertUploaded("/snapshots/org/example/sample/0.0.1-SNAPSHOT/sample-0.0.1-.*.jar")
        assertUploaded("/snapshots/org/example/sample/0.0.1-SNAPSHOT/ivy-0.0.1-.*.xml")
    }

    @Test
    fun `publishes snapshots with ivy pattern`() {
        projectDir.resolve("settings.gradle").write(
            """
            rootProject.name = 'sample'
        """
        )

        projectDir.resolve("build.gradle").write(
            """
            plugins {
                id('java-library')
                id('$publishPluginId')
                id('io.github.gradle-nexus.publish-plugin')
            }
            group = 'org.example'
            version = '0.0.1-SNAPSHOT'
            publishing {
                publications {
                     $publishPluginContent
                }
            }
            nexusPublishing {
                ivyPatternLayout {
                    artifact "[organisation]/[module]_foo/[revision]/[artifact]-[revision](-[classifier])(.[ext])"
                    m2compatible = true
                }
                publicationType = io.github.gradlenexus.publishplugin.NexusPublishExtension.PublicationType.$publicationTypeName
                repositories {
                    myNexus {
                        nexusUrl = uri('${server.baseUrl()}/shouldNotBeUsed')
                        snapshotRepositoryUrl = uri('${server.baseUrl()}/snapshots')
                        allowInsecureProtocol = true
                        username = 'username'
                        password = 'password'
                    }
                }
            }
            """
        )

        expectArtifactUploads("/snapshots")

        val result = run("publishToMyNexus")

        assertSkipped(result, ":initializeMyNexusStagingRepository")
        assertUploaded("/snapshots/org/example/sample_foo/0.0.1-SNAPSHOT/sample-0.0.1-.*.jar")
        assertUploaded("/snapshots/org/example/sample_foo/0.0.1-SNAPSHOT/ivy-0.0.1-.*.xml")
    }

    @Test
    fun `publishes to two Nexus repositories`(
        @MethodScopeWiremockResolver.MethodScopedWiremockServer @WiremockResolver.Wiremock
        otherServer: WireMockServer
    ) {
        projectDir.resolve("settings.gradle").write(
            """
            rootProject.name = 'sample'
            """
        )
        projectDir.resolve("build.gradle").write(
            """
            plugins {
                id('java-library')
                id('$publishPluginId')
                id('io.github.gradle-nexus.publish-plugin')
            }
            group = 'org.example'
            version = '0.0.1'
            publishing {
                publications {
                                        $publishPluginContent

                }
            }
            nexusPublishing {
                publicationType = io.github.gradlenexus.publishplugin.NexusPublishExtension.PublicationType.$publicationTypeName
                repositories {
                    myNexus {
                        nexusUrl = uri('${server.baseUrl()}')
                        snapshotRepositoryUrl = uri('${server.baseUrl()}/snapshots/')
                        allowInsecureProtocol = true
                        username = 'username'
                        password = 'password'
                    }
                    someOtherNexus {
                        nexusUrl = uri('${otherServer.baseUrl()}')
                        snapshotRepositoryUrl = uri('${otherServer.baseUrl()}/snapshots/')
                        allowInsecureProtocol = true
                        username = 'someUsername'
                        password = 'somePassword'
                    }
                }
            }
            """
        )

        val otherStagingProfileId = "otherStagingProfileId"
        val otherStagingRepositoryId = "orgexample-43"
        stubStagingProfileRequest("/staging/profiles", mapOf("id" to STAGING_PROFILE_ID, "name" to "org.example"))
        stubStagingProfileRequest(
            "/staging/profiles",
            mapOf("id" to otherStagingProfileId, "name" to "org.example"),
            wireMockServer = otherServer
        )
        stubCreateStagingRepoRequest("/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)
        stubCreateStagingRepoRequest(
            "/staging/profiles/$otherStagingProfileId/start",
            otherStagingRepositoryId,
            wireMockServer = otherServer
        )
        expectArtifactUploads("/staging/deployByRepositoryId/$STAGED_REPOSITORY_ID")
        expectArtifactUploads("/staging/deployByRepositoryId/$otherStagingRepositoryId", wireMockServer = otherServer)

        val result = run("publishToMyNexus", "publishToSomeOtherNexus")

        assertSuccess(result, ":initializeMyNexusStagingRepository")
        assertSuccess(result, ":initializeSomeOtherNexusStagingRepository")
        Assertions.assertThat(result.output)
            .containsOnlyOnce("Created staging repository '$STAGED_REPOSITORY_ID' at ${server.baseUrl()}/repositories/$STAGED_REPOSITORY_ID/content/")
        Assertions.assertThat(result.output)
            .containsOnlyOnce("Created staging repository '$otherStagingRepositoryId' at ${otherServer.baseUrl()}/repositories/$otherStagingRepositoryId/content/")
        server.verify(
            WireMock.postRequestedFor(WireMock.urlEqualTo("/staging/profiles/$STAGING_PROFILE_ID/start"))
                .withRequestBody(WireMock.matchingJsonPath("\$.data[?(@.description == 'org.example:sample:0.0.1')]"))
        )
        otherServer.verify(
            WireMock.postRequestedFor(WireMock.urlEqualTo("/staging/profiles/$otherStagingProfileId/start"))
                .withRequestBody(WireMock.matchingJsonPath("\$.data[?(@.description == 'org.example:sample:0.0.1')]"))
        )

        assertUploadedToStagingRepo("/org/example/sample/0.0.1/sample-0.0.1.jar")
        assertUploadedToStagingRepo("/org/example/sample/0.0.1/ivy-0.0.1.xml")
        assertUploadedToStagingRepo(
            "/org/example/sample/0.0.1/sample-0.0.1.jar",
            stagingRepositoryId = otherStagingRepositoryId,
            wireMockServer = otherServer
        )
        assertUploadedToStagingRepo(
            "/org/example/sample/0.0.1/ivy-0.0.1.xml",
            stagingRepositoryId = otherStagingRepositoryId,
            wireMockServer = otherServer
        )
    }

    @Test
    fun `publishes to Nexus`() {
        projectDir.resolve("settings.gradle").write(
            """
            rootProject.name = 'sample'
            """
        )
        projectDir.resolve("build.gradle").write(
            """
            plugins {
                id('java-library')
                id('$publishPluginId')
                id('io.github.gradle-nexus.publish-plugin')
            }
            group = 'org.example'
            version = '0.0.1'
            publishing {
                publications {
                   $publishPluginContent
                }
            }
            nexusPublishing {
                publicationType = io.github.gradlenexus.publishplugin.NexusPublishExtension.PublicationType.$publicationTypeName
                repositories {
                    myNexus {
                        nexusUrl = uri('${server.baseUrl()}')
                        snapshotRepositoryUrl = uri('${server.baseUrl()}/snapshots/')
                        allowInsecureProtocol = true
                        username = 'username'
                        password = 'password'
                    }
                    someOtherNexus {
                        nexusUrl = uri('http://example.org')
                        snapshotRepositoryUrl = uri('http://example.org/snapshots/')
                    }
                }
            }
            """
        )

        stubStagingProfileRequest("/staging/profiles", mapOf("id" to STAGING_PROFILE_ID, "name" to "org.example"))
        stubCreateStagingRepoRequest("/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)
        expectArtifactUploads("/staging/deployByRepositoryId/$STAGED_REPOSITORY_ID")

        val result = run("publishToMyNexus")

        assertSuccess(result, ":initializeMyNexusStagingRepository")
        Assertions.assertThat(result.output)
            .containsOnlyOnce("Created staging repository '$STAGED_REPOSITORY_ID' at ${server.baseUrl()}/repositories/$STAGED_REPOSITORY_ID/content/")
        assertNotConsidered(result, ":initializeSomeOtherNexusStagingRepository")
        server.verify(
            WireMock.postRequestedFor(WireMock.urlEqualTo("/staging/profiles/$STAGING_PROFILE_ID/start"))
                .withRequestBody(WireMock.matchingJsonPath("\$.data[?(@.description == 'org.example:sample:0.0.1')]"))
        )
        assertUploadedToStagingRepo("/org/example/sample/0.0.1/ivy-0.0.1.xml")
        assertUploadedToStagingRepo("/org/example/sample/0.0.1/sample-0.0.1.jar")
    }

    @Test
    fun `can be used with lazily applied Gradle Plugin Development Plugin`() {
        projectDir.resolve("settings.gradle").write(
            """
            rootProject.name = 'sample'
            include 'gradle-plugin'
        """
        )
        if (gradleVersion < GradleVersion.version("5.0")) {
            projectDir.resolve("settings.gradle").append(
                """
                enableFeaturePreview("STABLE_PUBLISHING")
                """
            )
        }

        projectDir.resolve("build.gradle").write(
            """
            plugins {
                id('io.github.gradle-nexus.publish-plugin')
            }
            nexusPublishing {
                publicationType = io.github.gradlenexus.publishplugin.NexusPublishExtension.PublicationType.$publicationTypeName
                repositories {
                    sonatype {
                        nexusUrl = uri('${server.baseUrl()}')
                        stagingProfileId = '$STAGING_PROFILE_ID'
                        allowInsecureProtocol = true
                        username = 'username'
                        password = 'password'
                    }
                }
            }
            """
        )

        val pluginDir = Files.createDirectories(projectDir.resolve("gradle-plugin"))
        pluginDir.resolve("build.gradle").write(
            """
            plugins {
                id('$publishPluginId')
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
            """
        )
        val srcDir = Files.createDirectories(pluginDir.resolve("src/main/java/org/example/"))
        srcDir.resolve("FooPlugin.java").write(
            """
            import org.gradle.api.*;
            public class FooPlugin implements Plugin<Project> {
                public void apply(Project p) {}
            }
            """
        )

        stubCreateStagingRepoRequest("/staging/profiles/$STAGING_PROFILE_ID/start", STAGED_REPOSITORY_ID)
        expectArtifactUploads("/staging/deployByRepositoryId/$STAGED_REPOSITORY_ID")

        val result = run("publishToSonatype", "-s")

        assertSuccess(result, ":initializeSonatypeStagingRepository")
        assertUploadedToStagingRepo("/org/example/gradle-plugin/0.0.1/gradle-plugin-0.0.1.jar")
        assertUploadedToStagingRepo("/org/example/gradle-plugin/0.0.1/ivy-0.0.1.xml")
        assertUploadedToStagingRepo("/org.example.foo/org.example.foo.gradle.plugin/0.0.1/ivy-0.0.1.xml")
    }
}
