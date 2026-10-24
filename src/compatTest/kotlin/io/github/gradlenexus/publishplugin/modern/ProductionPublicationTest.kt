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

package io.github.gradlenexus.publishplugin.modern

import com.google.gson.JsonParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Uses actual configured publications, never a synthesized module or marker POM. */
internal class ProductionPublicationTest {
    private val repository = File(requireNotNull(System.getProperty("modernTest.publicationRepository")))
    private val version = requireNotNull(System.getProperty("modernTest.publicationVersion"))
    private val module = File(repository, "io/github/gradle-nexus/publish-plugin/$version")

    @Test
    fun `actual publication contains final binary owned sources public documentation and generated metadata`() {
        val binary = artifact(".jar") { !it.name.endsWith("-sources.jar") && !it.name.endsWith("-javadoc.jar") }
        assertThat(digest(binary)).isEqualTo(digest(ModernTestPaths.candidateJar))
        val sources = entries(artifact("-sources.jar"))
        val ownedRoots = listOf("src/main")
        val expected = ownedRoots.flatMap { root ->
            File(ModernTestPaths.checkoutRoot, root).listFiles()!!.filter { it.isDirectory }.flatMap { language ->
                language.walkTopDown().filter { it.isFile && it.extension in setOf("java", "kt", "groovy") }
                    .map { it.relativeTo(language).invariantSeparatorsPath to it }.toList()
            }
        }
        assertThat(sources.filter { it.substringAfterLast('.') in setOf("java", "kt", "groovy") })
            .containsExactlyInAnyOrderElementsOf(expected.map { it.first })
        ZipFile(artifact("-sources.jar")).use { zip ->
            expected.forEach { (name, file) ->
                val actual = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
                assertThat(actual).describedAs(name).isEqualTo(file.readBytes())
            }
        }
        assertThat(sources.none { it.contains("compatTest") || it.endsWith("Test.kt") }).isTrue()
        val docs = entries(artifact("-javadoc.jar"))
        assertThat(docs).contains(
            "io/github/gradlenexus/publishplugin/settings/NexusPublishSettings.html",
            "io/github/gradlenexus/publishplugin/settings/NexusRepositorySettings.html",
            "io/github/gradlenexus/publishplugin/tasks/NexusRepositorySession.html",
            "io/github/gradlenexus/publishplugin/tasks/NexusRemotePublish.html"
        )
        assertThat(docs.none { it.contains("NexusSettingsInternals") || it.contains("/internal/") }).isTrue()
        val pom = xml(artifact(".pom"))
        assertThat(pom.getElementsByTagName("artifactId").item(0).textContent).isEqualTo("publish-plugin")
        assertThat(pom.getElementsByTagName("groupId").item(0).textContent).isEqualTo("io.github.gradle-nexus")
        assertThat(pom.getElementsByTagName("version").item(0).textContent).isEqualTo(version)
        assertThat(pom.getElementsByTagName("dependency").length).isZero()
        val metadata = JsonParser().parse(artifact(".module").readText()).asJsonObject
        assertThat(metadata.getAsJsonObject("component").get("module").asString).isEqualTo("publish-plugin")
        val variants = metadata.getAsJsonArray("variants").map { it.asJsonObject }
        variants.forEach { assertThat(it.getAsJsonArray("dependencies")?.size() ?: 0).isZero() }
        val publishedFiles = variants.flatMap { variant -> variant.getAsJsonArray("files")?.map { it.asJsonObject } ?: emptyList() }
        val logicalFiles = listOf("", "sources", "javadoc").associateBy { classifier ->
            "publish-plugin-$version${if (classifier.isEmpty()) "" else "-$classifier"}.jar"
        }
        assertThat(publishedFiles.map { it.get("url").asString }).containsExactlyInAnyOrderElementsOf(logicalFiles.keys)
        val snapshots = xml(File(module, "maven-metadata.xml")).getElementsByTagName("snapshotVersion")
        publishedFiles.forEach { entry ->
            val classifier = logicalFiles.getValue(entry.get("url").asString)
            val snapshot = (0 until snapshots.length).map { snapshots.item(it) as org.w3c.dom.Element }.single {
                it.getElementsByTagName("extension").item(0).textContent == "jar" &&
                    (it.getElementsByTagName("classifier").item(0)?.textContent ?: "") == classifier
            }
            val resolvedVersion = snapshot.getElementsByTagName("value").item(0).textContent
            val physical = File(module, "publish-plugin-$resolvedVersion${if (classifier.isEmpty()) "" else "-$classifier"}.jar")
            assertThat(physical).isFile()
            assertThat(physical.length()).isEqualTo(entry.get("size").asLong)
            assertThat(digest(physical)).isEqualTo(entry.get("sha256").asString)
        }
        val marker = File(repository, "io/github/gradle-nexus/publish-plugin/io.github.gradle-nexus.publish-plugin.gradle.plugin/$version")
        val markerPom = xml(marker.listFiles()!!.single { it.extension == "pom" })
        val dependency = markerPom.getElementsByTagName("dependency").item(0)
        assertThat(dependency.textContent).contains("io.github.gradle-nexus", "publish-plugin", version)
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `actual generated marker and module expose Settings accessors and public task APIs`(runtime: ModernRuntime) {
        val project = directory()
        File(project, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { maven { url = uri("${repository.toURI()}") } } }
            plugins { id("io.github.gradle-nexus.publish-plugin") version "$version" }
            rootProject.name = "published-settings"
            nexusPublishing { useStaging.set(false); repositories { create("probe") } }
            """.trimIndent() + "\n"
        )
        File(project, "build.gradle.kts").writeText(
            """
            import io.github.gradlenexus.publishplugin.tasks.NexusRepositorySession
            import io.github.gradlenexus.publishplugin.tasks.NexusRemotePublish
            check(tasks.withType<NexusRepositorySession>().names.size == 3)
            check(tasks.withType<NexusRemotePublish>().names.size == 1)
            tasks.withType<NexusRemotePublish>().configureEach { check(repositoryAlias.get() == "probe") }
            tasks.register("publishedApiProof") { doLast { logger.lifecycle("PUBLISHED_SETTINGS_API") } }
            """.trimIndent() + "\n"
        )
        ModernGradleBuild(project, false, runtime).storeAndReuse("publishedApiProof") { result ->
            assertThat(result.output).contains("PUBLISHED_SETTINGS_API")
            assertThat(result.tasks.none { it.path.contains("_session") || it.path.contains("_upload") || it.path.contains("resetNexus") }).isTrue()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `actual generated marker retains legacy floor and current plugin resolution`(current: Boolean) {
        val project = directory()
        File(project, "settings.gradle").writeText("pluginManagement { repositories { maven { url = uri('${repository.toURI()}') } } }\nrootProject.name = 'published-legacy'\n")
        File(project, "build.gradle").writeText(
            """
            plugins { id 'io.github.gradle-nexus.publish-plugin' version '$version' }
            assert System.getProperty('java.specification.version') == findProperty('expectedJava')
            assert extensions.findByName('nexusPublishing') != null
            assert plugins.getPlugin('io.github.gradle-nexus.publish-plugin') instanceof io.github.gradlenexus.publishplugin.NexusPublishPlugin
            """.trimIndent() + "\n"
        )
        runLegacy(project, "closeStagingRepositories", current)
    }

    @Test
    fun `actual generated marker survives Gradle 6_9_4 Java 8 whole archive instrumentation`() {
        val project = directory()
        File(project, "settings.gradle").writeText("pluginManagement { repositories { maven { url = uri('${repository.toURI()}') } } }\nrootProject.name = 'published-instrumented-legacy'\n")
        File(project, "build.gradle").writeText(
            """
            plugins { id 'io.github.gradle-nexus.publish-plugin' version '$version' }
            assert gradle.gradleVersion == '6.9.4'
            assert System.getProperty('java.specification.version') == '1.8'
            assert extensions.findByName('nexusPublishing') != null
            assert plugins.getPlugin('io.github.gradle-nexus.publish-plugin') instanceof io.github.gradlenexus.publishplugin.NexusPublishPlugin
            """.trimIndent() + "\n"
        )
        runLegacy(project, "closeStagingRepositories", version = "6.9.4")
    }

    private fun directory(): File =
        File(ModernTestPaths.workspaceRoot, "production-publication/${UUID.randomUUID()}/consumer").apply { mkdirs() }
    private fun artifact(suffix: String, accept: (File) -> Boolean = { true }): File =
        module.listFiles()!!.single { it.name.endsWith(suffix) && accept(it) }
    private fun entries(file: File): Set<String> =
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            assertThat(names.size).isEqualTo(names.toSet().size)
            names.toSet()
        }
    private fun digest(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun xml(file: File) =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(file)
}
