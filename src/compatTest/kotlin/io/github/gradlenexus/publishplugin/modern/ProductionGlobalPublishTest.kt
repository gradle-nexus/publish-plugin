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

import com.google.gson.Gson
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.util.UUID

/** Own-project lifecycle dependencies use immutable unit paths, independent of public root alias naming. */
internal class ProductionGlobalPublishTest {
    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `root child and unqualified publish select only their own publishing units`(runtime: ModernRuntime) {
        listOf(":publish" to setOf(":"), ":child:publish" to setOf(":child"), "publish" to setOf(":", ":child", ":empty", ":other")).forEach { (request, selected) ->
            val project = directory()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                configure(project, remote)
                var before = 0
                val previousInvocations = mutableMapOf<String, String>()
                ModernGradleBuild(project, false, runtime).storeAndReuse(request) { result ->
                    verifySelected(project, remote, result, selected, before, previousInvocations)
                    before = remote.requests.size
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `publish and explicit child aliases deduplicate both format contexts`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote)
            var before = 0
            val previousInvocations = mutableMapOf<String, String>()
            ModernGradleBuild(project, false, runtime).storeAndReuse(":child:publish", ":child:publishToMaven", ":child:publishToIvy") { result ->
                verifySelected(project, remote, result, setOf(":child"), before, previousInvocations)
                assertThat(result.tasks.count { it.path.endsWith("_upload") }).isEqualTo(2)
                before = remote.requests.size
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `excluded global publish preserves native other repository while native tasks and prepare never enroll Nexus`(runtime: ModernRuntime) {
        listOf(true, false).forEach { excluded ->
            val project = directory()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                configure(project, remote, excluded)
                val other = File(project, "other-repository")
                File(project, "child/build.gradle").appendText("publishing.repositories.maven { name='Other';url=uri('${other.toURI()}') }\n")
                val build = ModernGradleBuild(project, false, runtime)
                val request = if (excluded) ":child:publish" else ":child:publishMainPublicationToOtherRepository"
                build.storeAndReuse(request) { result ->
                    assertNoEnrollment(result, remote)
                    assertThat(result.task(":child:publishMainPublicationToOtherRepository")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                    assertThat(File(other, "probe/child/1.0/child-1.0.jar").readBytes()).isEqualTo(File(project, "child/build/libs/child-1.0.jar").readBytes())
                    // Inventory actual local native exporters separately; no root selection or remote success is inferred from them.
                    println("Native publish local exporters: " + result.tasks.filter { it.path.contains("exportNexus") }.map { it.path })
                }
                build.storeAndReuse(":child:prepareNexusMavenBundle") { result ->
                    assertNoEnrollment(result, remote)
                    assertThat(result.tasks.map { it.path }).contains(":child:prepareNexusMavenBundle")
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `empty publishing and plain projects stay local and public tasks are discoverable without HTTP`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote)
            val previousInvocations = mutableMapOf<String, String>()
            val build = ModernGradleBuild(project, false, runtime)
            build.storeAndReuse(":empty:publish") { result ->
                verifySelected(project, remote, result, setOf(":empty"), 0, previousInvocations)
                assertThat(result.tasks.none { it.path.startsWith(":empty:") && (it.path.contains("compile") || it.path.contains("sign") || it.path.endsWith(":jar")) }).isTrue()
                assertThat(remote.requests).isEmpty()
            }
            build.storeAndReuse(":plain:plainWitness", "tasks") { result ->
                assertThat(result.output).contains("PLAIN_WITHOUT_PUBLISHING", "Publishing tasks")
                listOf("publishToMaven", "publishAllProjectsToMaven", "initializeMavenStagingRepository", "findMavenStagingRepository", "closeMavenStagingRepository", "releaseMavenStagingRepository", "closeAndReleaseMavenStagingRepository", "closeStagingRepositories", "releaseStagingRepositories", "closeAndReleaseStagingRepositories").forEach { name ->
                    assertThat(result.output).contains("$name - ")
                }
                assertThat(result.tasks.map { it.path }).containsExactlyInAnyOrder(":plain:plainWitness", ":tasks")
                assertThat(remote.requests).isEmpty()
            }
        }
    }

    private fun verifySelected(project: File, remote: MemoryRepository, result: BuildResult, selected: Set<String>, before: Int, previousInvocations: MutableMap<String, String>) {
        val records = File(project, "build/nexus-publishing/workflows").listFiles()!!.map { File(it, "plan.json") }.filter { it.isFile }.associate { file ->
            val record = Gson().fromJson(file.readText(), Map::class.java)
            record["repository"] as String to record
        }
        assertThat(records.keys).containsExactlyInAnyOrder("maven", "ivy")
        records.forEach { (repository, record) ->
            val actual = (record["units"] as List<*>).map { it as String }.toSet()
            assertThat(actual).isEqualTo(selected.map { "p${paths.indexOf(it)}" }.toSet())
            val invocation = record["invocation"] as String
            assertThat(invocation).isNotEmpty().isNotEqualTo(previousInvocations[repository])
            previousInvocations[repository] = invocation
        }
        assertThat(previousInvocations.values.toSet()).hasSize(2)
        val delta = remote.requests.drop(before)
        assertThat(delta.none { it.first == "POST" }).isTrue()
        val puts = delta.filter { it.first == "PUT" }
        val producers = selected.filter { it != ":empty" }.map { if (it == ":") "root" else it.removePrefix(":") }
        if (producers.isEmpty()) assertThat(puts).isEmpty() else assertThat(puts).isNotEmpty()
        assertThat(puts.groupBy { it.second }.values.all { it.size == 1 }).describedAs("one upload per destination per invocation").isTrue()
        assertThat(puts.all { put -> listOf("maven", "ivy").any { format -> producers.any { put.second.startsWith("/$format/probe/$it/") } } }).isTrue()
        producers.forEach { producer ->
            val jar = File(project, (if (producer == "root") "" else "$producer/") + "build/libs/$producer-1.0.jar")
            listOf("maven", "ivy").forEach { format -> assertThat(remote.files["/$format/probe/$producer/1.0/$producer-1.0.jar"]).isEqualTo(jar.readBytes()) }
            java.util.jar.JarFile(jar).use { assertThat(it.getJarEntry("probe/Example.class")).isNotNull() }
        }
        paths.filter { it !in selected }.forEach { path ->
            listOf("maven", "ivy").forEach { format -> assertThat(result.output).doesNotContain("NEXUS-RESOLVE $path $format") }
            val prefix = if (path == ":") ":" else "$path:"
            assertThat(result.tasks.none { it.path == prefix + "prepareNexusMavenBundle" || it.path == prefix + "prepareNexusIvyBundle" }).isTrue()
        }
    }

    private fun assertNoEnrollment(result: BuildResult, remote: MemoryRepository) {
        assertThat(remote.requests).isEmpty()
        assertThat(result.tasks.none { it.path.startsWith(":nexus_") }).isTrue()
        assertThat(result.output).doesNotContain("NEXUS-RESOLVE", "NEXUS-WORKFLOW")
    }

    private fun configure(project: File, remote: MemoryRepository, excludeChild: Boolean = false) {
        val repository = File(project.parentFile, "repository")
        val version = "2.0.1-SNAPSHOT"
        fun pom(group: String, artifact: String, dependencies: String = "", packaging: String = "jar") {
            val file = File(repository, "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.pom")
            file.parentFile.mkdirs()
            file.writeText("""<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$artifact</artifactId><version>$version</version><packaging>$packaging</packaging>$dependencies</project>""")
        }
        File(repository, "io/github/gradle-nexus/publish-plugin/$version/publish-plugin-$version.jar").apply {
            parentFile.mkdirs()
            ModernTestPaths.candidateJar.copyTo(this)
        }
        pom("io.github.gradle-nexus", "publish-plugin")
        pom("io.github.gradle-nexus.publish-plugin", "io.github.gradle-nexus.publish-plugin.gradle.plugin", "<dependencies><dependency><groupId>io.github.gradle-nexus</groupId><artifactId>publish-plugin</artifactId><version>$version</version></dependency></dependencies>", "pom")
        File(project, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { maven { url = uri("${repository.toURI()}") } } }
            plugins { id("io.github.gradle-nexus.publish-plugin") version "$version" }
            rootProject.name = "root"
            include("child", "empty", "other", "plain")
            nexusPublishing {
                useStaging.set(false)
                ${if (excludeChild) "excludedProjects.add(\":child\")" else ""}
                repositories {
                    create("maven") { snapshotRepositoryUrl.set(java.net.URI.create("${remote.endpoint}maven/"));allowInsecureProtocol.set(true) }
                    create("ivy") { publicationType.set(io.github.gradlenexus.publishplugin.settings.NexusRepositorySettings.PublicationType.IVY);snapshotRepositoryUrl.set(java.net.URI.create("${remote.endpoint}ivy/"));allowInsecureProtocol.set(true) }
                }
            }
            """.trimIndent() + "\n"
        )
        listOf("root", "child", "other").forEach { name ->
            val module = if (name == "root") project else File(project, name).apply { mkdirs() }
            File(module, "build.gradle").writeText(
                """
                plugins { id 'java-library';id 'maven-publish';id 'ivy-publish' }
                group='probe';version='1.0'
                publishing.publications {
                    main(MavenPublication) { from components.java;artifactId='$name' }
                    ivy(IvyPublication) { from components.java;module='$name' }
                }
                """.trimIndent() + "\n"
            )
            File(module, "src/main/java/probe/Example.java").apply {
                parentFile.mkdirs()
                writeText("""package probe; public class Example { public static String identity() { return "$name"; } }""" + "\n")
            }
        }
        File(project, "build.gradle").appendText("tasks.matching { it.name.startsWith('nexus_') }.configureEach { assert group==null }\n")
        File(project, "empty/build.gradle").apply {
            parentFile.mkdirs()
            writeText("plugins { id 'maven-publish' }\nassert !plugins.hasPlugin('java') && !plugins.hasPlugin('signing')\n")
        }
        File(project, "plain/build.gradle").apply {
            parentFile.mkdirs()
            writeText("assert !plugins.hasPlugin('publishing') && !plugins.hasPlugin('java') && !plugins.hasPlugin('signing')\nassert tasks.findByName('publish')==null\ntasks.register('plainWitness') { doLast { logger.lifecycle('PLAIN_WITHOUT_PUBLISHING') } }\n")
        }
    }

    private val paths = listOf(":", ":child", ":empty", ":other", ":plain")
    private fun directory() =
        File(ModernTestPaths.workspaceRoot, "production-global-publish/${UUID.randomUUID()}/consumer").apply { mkdirs() }
}
