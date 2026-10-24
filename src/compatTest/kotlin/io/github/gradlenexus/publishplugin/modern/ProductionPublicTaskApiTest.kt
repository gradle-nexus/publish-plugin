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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.util.UUID

/** Normal plugin resolution: no TestKit injection or project plugin/classpath workaround. */
internal class ProductionPublicTaskApiTest {
    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `normal marker resolution exposes Kotlin settings accessor and root Kotlin and Groovy upload hooks`(runtime: ModernRuntime) {
        listOf("kotlin", "groovy").forEach { language ->
            val project = directory()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                configure(project, remote)
                remote.response = { method, path ->
                    if (method == "GET" && path.startsWith("/hooks/")) MemoryRepository.Response(200, data = "ok") else null
                }
                File(project, if (language == "kotlin") "build.gradle.kts" else "build.gradle").writeText(hooks(language, remote.endpoint.toString()))
                var before = 0
                ModernGradleBuild(project, false, runtime).storeAndReuse(":child:publishToProbe", "publicTypesWitness") { result ->
                    assertThat(result.output).contains("PUBLIC_TYPES_OK", "PUBLIC_UPLOAD_PREREQUISITE", "PUBLIC_UPLOAD_BEFORE", "PUBLIC_UPLOAD_AFTER")
                    val delta = remote.requests.drop(before)
                    val putIndices = delta.indices.filter { delta[it].first == "PUT" }
                    assertThat(putIndices).isNotEmpty()
                    val hookPaths = delta.filter { it.first == "GET" && it.second.startsWith("/hooks/") }.map { it.second }
                    assertThat(hookPaths).containsExactly("/hooks/prerequisite", "/hooks/before", "/hooks/after")
                    assertThat(delta.indexOfFirst { it.second == "/hooks/before" }).isLessThan(putIndices.first())
                    assertThat(delta.indexOfFirst { it.second == "/hooks/after" }).isGreaterThan(putIndices.last())
                    assertThat(delta.filter { it.first == "PUT" }.all { it.second.startsWith("/direct/probe/child/") }).isTrue()
                    assertThat(remote.files["/direct/probe/child/1.0/child-1.0.jar"]).isEqualTo(File(project, "child/build/libs/child-1.0.jar").readBytes())
                    assertThat(result.tasks.none { it.path == ":findProbeStagingRepository" || it.path == ":initializeProbeStagingRepository" }).isTrue()
                    before = remote.requests.size
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `precompiled convention uses explicit module dependency and the same public task identity`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote)
            remote.response = { method, path -> if (method == "GET" && path.startsWith("/hooks/")) MemoryRepository.Response(200, data = "ok") else null }
            val buildSrc = File(project, "buildSrc").apply { mkdirs() }
            File(buildSrc, "settings.gradle.kts").writeText("rootProject.name = \"public-hook-conventions\"\n")
            File(buildSrc, "build.gradle.kts").writeText(
                """
                plugins { `kotlin-dsl` }
                repositories { maven { url = uri("${File(project.parentFile, "repository").toURI()}") }; mavenCentral(); gradlePluginPortal() }
                dependencies { implementation("io.github.gradle-nexus:publish-plugin:$version") }
                """.trimIndent() + "\n"
            )
            File(buildSrc, "src/main/kotlin/public-hooks.gradle.kts").apply {
                parentFile.mkdirs()
                writeText(hooks("kotlin", remote.endpoint.toString()))
            }
            File(project, "build.gradle.kts").writeText("plugins { id(\"public-hooks\") }\n")
            var before = 0
            ModernGradleBuild(project, false, runtime).storeAndReuse(":child:publishToProbe", "publicTypesWitness") { result ->
                assertThat(result.output).contains("PUBLIC_TYPES_OK", "PUBLIC_UPLOAD_BEFORE", "PUBLIC_UPLOAD_AFTER")
                val delta = remote.requests.drop(before)
                assertThat(delta.filter { it.first == "GET" && it.second.startsWith("/hooks/") }.map { it.second }).containsExactly("/hooks/prerequisite", "/hooks/before", "/hooks/after")
                assertThat(delta.filter { it.first == "PUT" }).isNotEmpty()
                assertThat(remote.files["/direct/probe/child/1.0/child-1.0.jar"]).isEqualTo(File(project, "child/build/libs/child-1.0.jar").readBytes())
                before = remote.requests.size
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `public find type is exclusive and transition properties and CLI options own recovery`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote, staging = true)
            val state = java.util.concurrent.atomic.AtomicReference("open")
            remote.response = { method, path ->
                when {
                    method == "GET" && path.endsWith("/profile_repositories/profile") -> MemoryRepository.Response(200, data = """{"data":[{"repositoryId":"typed-recovery","description":"custom selected description"},{"repositoryId":"unrelated","description":"public-hooks"}]}""")
                    method == "POST" && path.endsWith("/bulk/close") -> if (state.compareAndSet("open", "closed")) MemoryRepository.Response(201) else MemoryRepository.Response(409)
                    method == "POST" && path.endsWith("/bulk/promote") -> if (state.compareAndSet("closed", "released")) MemoryRepository.Response(201) else MemoryRepository.Response(409)
                    method == "GET" && path.startsWith("/service/local/staging/repository/") -> if (state.get() == "released") MemoryRepository.Response(404, data = """{"repositoryId":"${path.substringAfterLast('/')}"}""") else MemoryRepository.Response(200, data = """{"repositoryId":"${path.substringAfterLast('/')}","type":"${state.get()}","transitioning":false}""")
                    else -> null
                }
            }
            File(project, "build.gradle.kts").writeText(
                """
                import io.github.gradlenexus.publishplugin.tasks.FindNexusStagingRepository
                import io.github.gradlenexus.publishplugin.tasks.NexusStagingRepositoryTransition
                check(tasks.withType<FindNexusStagingRepository>().names == setOf("findProbeStagingRepository"))
                tasks.withType<FindNexusStagingRepository>().configureEach {
                    check(repositoryAlias.get() == "probe")
                    descriptionRegex.set("custom selected")
                }
                tasks.named<NexusStagingRepositoryTransition>("closeProbeStagingRepository") { explicitRepositoryId.set("typed-recovery") }
                """.trimIndent() + "\n"
            )
            val build = ModernGradleBuild(project, false, runtime)
            var before = 0
            build.storeAndReuse("findProbeStagingRepository", "closeAndReleaseProbeStagingRepository") { result ->
                assertThat(result.output).contains("typed-recovery")
                assertThat(result.tasks.none { it.path.contains("prepareNexus") || it.path.contains("exportNexus") || it.path.endsWith("_upload") || it.path.endsWith("_session") }).isTrue()
                val delta = remote.requests.drop(before)
                assertThat(delta.filter { it.first == "POST" }.map { it.second }).containsExactly("/service/local/staging/bulk/close", "/service/local/staging/bulk/promote")
                assertThat(delta.filter { it.first == "POST" }.all { String(it.third, Charsets.UTF_8).contains("typed-recovery") }).isTrue()
                before = remote.requests.size
                state.set("open")
            }
            // A fresh standalone invocation uses the actual typed property, without public find.
            build.storeAndReuse("closeProbeStagingRepository") {
                val delta = remote.requests.drop(before)
                assertThat(delta.filter { it.first == "POST" }).hasSize(1)
                assertThat(String(delta.single { it.first == "POST" }.third, Charsets.UTF_8)).contains("typed-recovery")
                assertThat(delta.none { it.second.contains("profile_repositories") || it.first == "PUT" }).isTrue()
                before = remote.requests.size
                state.set("open")
            }
            repeat(2) { index ->
                val result = build.runRecovery(StagingRecoveryTask("closeProbeStagingRepository", "cli-recovery"))
                assertThat(result.output).contains(if (index == 0) "Configuration cache entry stored." else "Configuration cache entry reused.")
                val delta = remote.requests.drop(before)
                assertThat(delta.filter { it.first == "POST" }).hasSize(1)
                assertThat(String(delta.single { it.first == "POST" }.third, Charsets.UTF_8)).contains("cli-recovery")
                assertThat(delta.none { it.first == "PUT" || it.second.contains("profile_repositories") }).isTrue()
                before = remote.requests.size
                state.set("open")
            }
        }
    }

    private fun hooks(language: String, endpoint: String): String =
        if (language == "kotlin") {
            """
        import io.github.gradlenexus.publishplugin.tasks.NexusRemotePublish
        import io.github.gradlenexus.publishplugin.tasks.FindNexusStagingRepository
        import io.github.gradlenexus.publishplugin.tasks.NexusStagingRepositoryTransition
        val prerequisite = tasks.register("publicUploadPrerequisite") {
            doLast { logger.lifecycle("PUBLIC_UPLOAD_PREREQUISITE"); java.net.URI.create("${endpoint}hooks/prerequisite").toURL().readText() }
        }
        check(tasks.withType<FindNexusStagingRepository>().names == setOf("findProbeStagingRepository"))
        check(tasks.withType<NexusStagingRepositoryTransition>().names == setOf("closeProbeStagingRepository", "releaseProbeStagingRepository"))
        tasks.withType<NexusRemotePublish>().configureEach {
            check(repositoryAlias.get() == "probe")
            dependsOn(prerequisite)
            doFirst { logger.lifecycle("PUBLIC_UPLOAD_BEFORE"); java.net.URI.create("${endpoint}hooks/before").toURL().readText() }
            doLast { logger.lifecycle("PUBLIC_UPLOAD_AFTER"); java.net.URI.create("${endpoint}hooks/after").toURL().readText() }
        }
        tasks.register("publicTypesWitness") { doLast { logger.lifecycle("PUBLIC_TYPES_OK") } }
            """.trimIndent() + "\n"
        } else {
            """
        import io.github.gradlenexus.publishplugin.tasks.NexusRemotePublish
        import io.github.gradlenexus.publishplugin.tasks.FindNexusStagingRepository
        import io.github.gradlenexus.publishplugin.tasks.NexusStagingRepositoryTransition
        def prerequisite = tasks.register('publicUploadPrerequisite') {
            doLast { logger.lifecycle('PUBLIC_UPLOAD_PREREQUISITE'); new URL('${endpoint}hooks/prerequisite').text }
        }
        assert tasks.withType(FindNexusStagingRepository).names == ['findProbeStagingRepository'] as Set
        assert tasks.withType(NexusStagingRepositoryTransition).names == ['closeProbeStagingRepository','releaseProbeStagingRepository'] as Set
        tasks.withType(NexusRemotePublish).configureEach {
            assert repositoryAlias.get() == 'probe'
            dependsOn(prerequisite)
            doFirst { logger.lifecycle('PUBLIC_UPLOAD_BEFORE'); new URL('${endpoint}hooks/before').text }
            doLast { logger.lifecycle('PUBLIC_UPLOAD_AFTER'); new URL('${endpoint}hooks/after').text }
        }
        tasks.register('publicTypesWitness') { doLast { logger.lifecycle('PUBLIC_TYPES_OK') } }
            """.trimIndent() + "\n"
        }

    private fun configure(project: File, remote: MemoryRepository, staging: Boolean = false) {
        val repository = File(project.parentFile, "repository")
        fun pom(group: String, artifact: String, dependencies: String = "", packaging: String = "jar") {
            val file = File(repository, "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.pom")
            file.parentFile.mkdirs()
            file.writeText("""<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$artifact</artifactId><version>$version</version><packaging>$packaging</packaging>$dependencies</project>""")
        }
        val module = File(repository, "io/github/gradle-nexus/publish-plugin/$version/publish-plugin-$version.jar")
        module.parentFile.mkdirs()
        ModernTestPaths.candidateJar.copyTo(module)
        pom("io.github.gradle-nexus", "publish-plugin")
        pom("io.github.gradle-nexus.publish-plugin", "io.github.gradle-nexus.publish-plugin.gradle.plugin", "<dependencies><dependency><groupId>io.github.gradle-nexus</groupId><artifactId>publish-plugin</artifactId><version>$version</version></dependency></dependencies>", "pom")
        File(project, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { maven { url = uri("${repository.toURI()}") } } }
            plugins { id("io.github.gradle-nexus.publish-plugin") version "$version" }
            rootProject.name = "public-hooks"
            include("child")
            nexusPublishing {
                useStaging.set($staging)
                transitionCheckOptions { maxRetries.set(0); delayBetween.set(java.time.Duration.ZERO) }
                repositories {
                    create("probe") {
                        nexusUrl.set(java.net.URI.create("${remote.endpoint}service/local/"))
                        snapshotRepositoryUrl.set(java.net.URI.create("${remote.endpoint}direct/"))
                        stagingProfileId.set("profile")
                        allowInsecureProtocol.set(true)
                    }
                }
            }
            """.trimIndent() + "\n"
        )
        File(project, "child/build.gradle").apply {
            parentFile.mkdirs()
            writeText("plugins { id 'java-library'; id 'maven-publish' }; group='probe'; version='1.0'; publishing.publications { main(MavenPublication) { from components.java; artifactId='child' } }\n")
        }
        File(project, "child/src/main/java/probe/Example.java").apply {
            parentFile.mkdirs()
            writeText("package probe; public class Example {}\n")
        }
    }

    private fun directory() =
        File(ModernTestPaths.workspaceRoot, "production-public-task-api/${UUID.randomUUID()}/consumer").apply { mkdirs() }

    private val version = "2.0.1-SNAPSHOT"
}
