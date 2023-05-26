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

import org.junit.jupiter.api.Test

class IvyNexusPublishPluginTests : BaseNexusPublishPluginTests() {
    init {
        publishPluginId = "ivy-publish"
        publishPluginContent = """  ivyCustom(IvyPublication) {
                                        from(components.java)
                                    }"""
        publishGoalPrefix = "publishIvyCustom"
        publicationTypeName = "IVY"
        snapshotArtifactList = listOf(
            "sample-0.0.1-.*.jar",
            "ivy-0.0.1-.*.xml"
        )
        artifactList = listOf(
            "sample-0.0.1.jar",
            "ivy-0.0.1.xml"
        )
        pluginArtifactList = listOf(
            "/org/example/gradle-plugin/0.0.1/gradle-plugin-0.0.1.jar",
            "/org/example/gradle-plugin/0.0.1/ivy-0.0.1.xml",
            "/org.example.foo/org.example.foo.gradle.plugin/0.0.1/ivy-0.0.1.xml"
        )
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
                repositories {
                    myNexus {
                        nexusUrl = uri('${server.baseUrl()}/shouldNotBeUsed')
                        snapshotRepositoryUrl = uri('${server.baseUrl()}/snapshots')
                        allowInsecureProtocol = true
                        username = 'username'
                        password = 'password'

                        ivyPatternLayout {
                            artifact "[organisation]/[module]_foo/[revision]/[artifact]-[revision](-[classifier])(.[ext])"
                            m2compatible = true
                        }
                        publicationType = io.github.gradlenexus.publishplugin.NexusPublishExtension.PublicationType.$publicationTypeName
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
}
