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
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.util.UUID

internal class IndependentGradleBuildsTest {
    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `independent failures do not mask successful or skipped contexts on store and reuse`(runtime: ModernRuntime) {
        val parent = File(ModernTestPaths.workspaceRoot, "batch-proof/${UUID.randomUUID()}").apply { mkdirs() }
        val projects = listOf("success", "failure-one", "failure-two", "skip").associateWith { name ->
            File(parent, name).apply {
                mkdirs()
                File(this, "settings.gradle").writeText("rootProject.name='same-root-name'\n")
                File(this, "build.gradle").writeText(
                    """
                    abstract class WitnessProbe extends DefaultTask {
                        @OutputFile abstract RegularFileProperty getWitness()
                        @Input abstract Property<String> getIdentity()
                        @TaskAction void run() {
                            witness.get().asFile.text=identity.get()
                            if(identity.get().startsWith('failure')) throw new GradleException('EXPECTED_' + identity.get())
                        }
                    }
                    tasks.register('probe', WitnessProbe) {
                        witness.set(layout.projectDirectory.file('witness'))
                        identity.set('$name')
                        ${if (name == "skip") "onlyIf { false }" else ""}
                        outputs.upToDateWhen { false }
                    }
                    """.trimIndent() + "\n"
                )
            }
        }
        val batch = IndependentGradleBuilds(projects.values.toList(), runtime)
        repeat(2) { index ->
            val results = batch.run(projects.values.associateWith { listOf(":probe") })
            projects.forEach { (name, project) ->
                val result = results.getValue(project)
                assertThat(result.output).contains(if (index == 0) "Configuration cache entry stored." else "Configuration cache entry reused.")
                assertThat(result.task(":probe")!!.outcome).isEqualTo(
                    when (name) {
                        "success" -> TaskOutcome.SUCCESS
                        "failure-one", "failure-two" -> TaskOutcome.FAILED
                        else -> TaskOutcome.SKIPPED
                    }
                )
                if (name == "skip") {
                    assertThat(File(project, "witness")).doesNotExist()
                } else {
                    assertThat(File(project, "witness").readText()).isEqualTo(name)
                }
                if (name.startsWith("failure")) {
                    assertThat(IndependentGradleBuilds.failureOutput(result)).contains("EXPECTED_$name")
                        .doesNotContain("EXPECTED_" + if (name == "failure-one") "failure-two" else "failure-one")
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `shared runtime retains independent project inputs and configuration cache entries`(runtime: ModernRuntime) {
        val parent = File(ModernTestPaths.workspaceRoot, "runtime-isolation/${UUID.randomUUID()}").apply { mkdirs() }
        val builds = listOf("first", "second").associateWith { name ->
            val project = File(parent, name).apply { mkdirs() }
            File(project, "settings.gradle").writeText("rootProject.name='same-root-name'\n")
            File(project, "identity.txt").writeText(name)
            File(project, "build.gradle").writeText(
                """
                abstract class IdentityProbe extends DefaultTask {
                    @Input abstract Property<String> getIdentity()
                    @TaskAction void verify() { logger.lifecycle('ISOLATED_IDENTITY=' + identity.get()) }
                }
                tasks.register('probe', IdentityProbe) { identity.set(providers.fileContents(layout.projectDirectory.file('identity.txt')).asText) }
                """.trimIndent() + "\n"
            )
            ModernGradleBuild(project, false, runtime)
        }
        builds.forEach { (name, build) ->
            build.storeAndReuse(":probe") { assertThat(it.output).contains("ISOLATED_IDENTITY=$name") }
        }
        val first = builds.getValue("first").run(":probe")
        assertThat(first.output).contains("Configuration cache entry reused.", "ISOLATED_IDENTITY=first").doesNotContain("ISOLATED_IDENTITY=second")
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `fresh resolution homes share only the runtime and fetch current bytes from the same repository`(runtime: ModernRuntime) {
        val parent = File(ModernTestPaths.workspaceRoot, "resolution-isolation/${UUID.randomUUID()}").apply { mkdirs() }
        MemoryRepository(File(parent, "http-evidence")).use { remote ->
            val pids = mutableSetOf<String>()
            val homes = mutableSetOf<String>()
            listOf("first", "second").forEach { identity ->
                val project = File(parent, "$identity/consumer").apply { mkdirs() }
                File(project, "settings.gradle").writeText("rootProject.name='fresh-consumer'\n")
                File(project, "build.gradle").writeText(
                    """
                    plugins { id 'java-library' }
                    repositories { maven { url=uri('${remote.endpoint}');allowInsecureProtocol=true;metadataSources { mavenPom();ignoreGradleMetadataRedirection() } } }
                    dependencies { implementation 'probe:fresh:1.0' }
                    abstract class CacheWitness extends DefaultTask {
                        @Input abstract Property<String> getHome()
                        @TaskAction void witness() {
                            logger.lifecycle('RESOLUTION_HOME=' + home.get())
                            logger.lifecycle('RUNTIME_PID=' + ProcessHandle.current().pid())
                        }
                    }
                    tasks.register('resolveArtifact', Sync) { from configurations.compileClasspath;into layout.buildDirectory.dir('resolved') }
                    tasks.register('cacheWitness', CacheWitness) { home.set(gradle.gradleUserHomeDir.absolutePath) }
                    """.trimIndent() + "\n"
                )
                remote.files["/probe/fresh/1.0/fresh-1.0.pom"] = "<project><modelVersion>4.0.0</modelVersion><groupId>probe</groupId><artifactId>fresh</artifactId><version>1.0</version></project>".toByteArray()
                remote.files["/probe/fresh/1.0/fresh-1.0.jar"] = identity.toByteArray()
                val before = remote.requests.size
                assertThat(File(project.parentFile, "resolution-home/${runtime.version}")).doesNotExist()
                ModernGradleBuild(project, false, runtime, isolatedDependencyCache = true).storeAndReuse(":resolveArtifact", ":cacheWitness") { result ->
                    val home = Regex("RESOLUTION_HOME=(.+)").find(result.output)!!.groupValues[1].trim()
                    assertThat(home).isEqualTo(File(project.parentFile, "resolution-home/${runtime.version}").absolutePath)
                    homes.add(home)
                    pids.add(Regex("RUNTIME_PID=(\\d+)").find(result.output)!!.groupValues[1])
                    assertThat(File(project, "build/resolved/fresh-1.0.jar").readBytes()).isEqualTo(identity.toByteArray())
                }
                val gets = remote.requests.drop(before).filter { it.first == "GET" }.map { it.second }
                assertThat(gets).contains("/probe/fresh/1.0/fresh-1.0.pom", "/probe/fresh/1.0/fresh-1.0.jar")
            }
            assertThat(homes).hasSize(2)
            assertThat(pids).describedAs("fresh caches reuse one owning TestKit daemon").hasSize(1)
        }
    }
}
