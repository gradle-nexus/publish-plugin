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

/** Actual Settings freeze boundaries, provider tracking and root delivery. */
internal class ProductionSettingsFreezeTest {
    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `unchanged settings verification supports strict store and reuse`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote)
            ModernGradleBuild(project, false, runtime).storeAndReuse("freezeWitness") { result ->
                assertThat(result.output).contains("SETTINGS_FREEZE_TASK_EXECUTED")
            }
            assertThat(remote.requests.map { it.first to it.second }).containsExactly("GET" to "/witness", "GET" to "/witness")
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `later settings evaluated includes and scalar changes reject before task or HTTP`(runtime: ModernRuntime) {
        listOf("include", "scalar").forEach { mode ->
            val project = directory()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                configure(project, remote)
                File(project, "late").mkdirs()
                val mutation = if (mode == "include") "settings.include('late')" else "settings.nexusPublishing.clientTimeout.set(java.time.Duration.ofSeconds(17))"
                File(project, "settings.gradle").appendText("\ngradle.settingsEvaluated { settings -> $mutation }\n")
                repeat(2) {
                    val result = ModernGradleBuild(project, false, runtime).failWithDiagnostic(if (mode == "include") "NEXUS_SETTINGS_CHANGED_AFTER_FREEZE" else "final", "freezeWitness")
                    assertThat(result.output).contains(if (mode == "include") "project paths" else "clientTimeout").doesNotContain("SETTINGS_FREEZE_TASK_EXECUTED")
                    assertThat(remote.requests).isEmpty()
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `nested later projects loaded mutation must not evade freeze verification`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote)
            File(project, "settings.gradle").appendText(
                """
                gradle.settingsEvaluated { settings ->
                    gradle.projectsLoaded { settings.nexusPublishing.clientTimeout.set(java.time.Duration.ofSeconds(17)) }
                }
                """.trimIndent() + "\n"
            )
            val result = ModernGradleBuild(project, false, runtime).failWithDiagnostic("final", "freezeWitness")
            assertThat(result.output).contains("clientTimeout")
            assertThat(result.output).doesNotContain("SETTINGS_FREEZE_TASK_EXECUTED")
            assertThat(remote.requests).isEmpty()
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `nested late layout and repository mutations reject immediately including lazy registration`(runtime: ModernRuntime) {
        listOf("create", "register", "remove", "clear", "layout", "identical").forEach { mode ->
            val project = directory()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                configure(project, remote)
                val mutation = when (mode) {
                    "create" -> "settings.nexusPublishing.repositories.create('late')"
                    "register" -> "settings.nexusPublishing.repositories.register('late')"
                    "remove" -> "settings.nexusPublishing.repositories.remove(settings.nexusPublishing.repositories.getByName('probe'))"
                    "clear" -> "settings.nexusPublishing.repositories.clear()"
                    "layout" -> "settings.nexusPublishing.repositories.getByName('probe').ivyPatternLayout { println 'SETTINGS_FREEZE_LAYOUT_ACTION_EXECUTED';artifact('[artifact].[ext]') }"
                    else -> "settings.nexusPublishing.clientTimeout.set(java.time.Duration.ofMinutes(5))"
                }
                File(project, "settings.gradle").appendText("\ngradle.settingsEvaluated { settings -> gradle.projectsLoaded { $mutation } }\n")
                val result = ModernGradleBuild(project, false, runtime).failWithDiagnostic(if (mode == "identical") "final" else "NEXUS_SETTINGS_FROZEN", "freezeWitness")
                if (mode == "identical") assertThat(result.output).contains("clientTimeout")
                assertThat(result.output).doesNotContain("SETTINGS_FREEZE_TASK_EXECUTED", "SETTINGS_FREEZE_LAYOUT_ACTION_EXECUTED")
                assertThat(remote.requests).isEmpty()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `providers sample once at freeze and refresh from tracked inputs on the next invocation`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote)
            File(project, "settings.gradle").appendText(
                """
                def original=providers.environmentVariable('BoundaryFixture_USERNAME').get()
                def holder=[original]
                nexusPublishing.repositories.probe.username.set(providers.provider { holder[0] })
                gradle.settingsEvaluated {
                    holder[0]='changed-upstream-after-freeze'
                    if(nexusPublishing.repositories.probe.username.get()!=original) throw new GradleException('SETTINGS_FREEZE_FINALIZED_PROVIDER_CHANGED')
                }
                """.trimIndent() + "\n"
            )
            File(project, "build.gradle").appendText(
                """
                abstract class ProviderProbe extends DefaultTask {
                    @Internal abstract Property<String> getUsernameValue()
                    @Internal abstract Property<String> getExpectedValue()
                    @TaskAction void verify() {
                        if(usernameValue.get()!=expectedValue.get()) throw new GradleException('SETTINGS_FREEZE_ROOT_PROVIDER_MISMATCH')
                        logger.lifecycle('SETTINGS_FREEZE_PROVIDER_SNAPSHOT_OK')
                    }
                }
                def coordinator=extensions.getByType(io.github.gradlenexus.publishplugin.internal.modern.RootCoordinator)
                tasks.register('providerWitness',ProviderProbe) {
                    usernameValue.set(coordinator.repositories.find { it.name=='probe' }.username)
                    expectedValue.set(providers.environmentVariable('BoundaryFixture_USERNAME'))
                }
                """.trimIndent() + "\n"
            )
            listOf("first-" + UUID.randomUUID(), "changed-" + UUID.randomUUID()).forEach { username ->
                ModernGradleBuild(project, false, runtime, mapOf("BoundaryFixture_USERNAME" to username)).storeAndReuse("providerWitness") { result ->
                    assertThat(result.output).contains("SETTINGS_FREEZE_PROVIDER_SNAPSHOT_OK")
                    assertThat(result.output.contains(username)).describedAs("provider credential absent from logs").isFalse()
                    assertThat(remote.requests).isEmpty()
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `explicit layout snapshot reaches the root and rejects late actions`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote)
            File(project, "settings.gradle").appendText(
                """
                nexusPublishing.repositories.probe {
                    publicationType.set(io.github.gradlenexus.publishplugin.settings.NexusRepositorySettings.PublicationType.IVY)
                    ivyPatternLayout { artifact('first/[artifact].[ext]');artifact('second/[artifact].[ext]');ivy('metadata/ivy.xml');m2compatible=true }
                }
                """.trimIndent() + "\n"
            )
            File(project, "build.gradle").appendText(
                """
                def spec=extensions.getByType(io.github.gradlenexus.publishplugin.internal.modern.RootCoordinator).repositories.find { it.name=='probe' }.spec
                assert spec.explicitIvyLayout && spec.m2Compatible
                assert spec.artifactPatterns==['first/[artifact].[ext]','second/[artifact].[ext]'] && spec.ivyPatterns==['metadata/ivy.xml']
                """.trimIndent() + "\n"
            )
            val build = ModernGradleBuild(project, false, runtime)
            build.storeAndReuse("freezeWitness")
            val before = remote.requests.size
            File(project, "settings.gradle").appendText("\ngradle.settingsEvaluated { settings -> gradle.projectsLoaded { settings.nexusPublishing.repositories.probe.ivyPatternLayout { println 'SETTINGS_FREEZE_LAYOUT_ACTION_EXECUTED';artifact('changed/[artifact].[ext]') } } }\n")
            val result = build.failWithDiagnostic("NEXUS_SETTINGS_FROZEN", "freezeWitness")
            assertThat(result.output).contains("repositories.ivyPatternLayout").doesNotContain("SETTINGS_FREEZE_TASK_EXECUTED", "SETTINGS_FREEZE_LAYOUT_ACTION_EXECUTED")
            assertThat(remote.requests.size).isEqualTo(before)
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `ordinary lazy repositories and unrelated descriptor changes retain usable late root defaults`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote)
            val settings = File(project, "settings.gradle")
            settings.writeText(settings.readText().replace("repositories.create('probe')", "repositories.register('probe')"))
            settings.appendText(
                """
                include 'child'
                nexusPublishing.repositories.create('temporary')
                nexusPublishing.repositories.remove(nexusPublishing.repositories.getByName('temporary'))
                nexusPublishing.repositories.named('probe') {
                    snapshotRepositoryUrl.set(uri('${remote.endpoint}direct/'))
                    allowInsecureProtocol.set(true)
                }
                gradle.settingsEvaluated { settings ->
                    settings.rootProject.name='renamed-root'
                    settings.project(':child').projectDir=new File(settings.settingsDir,'relocated-child')
                }
                """.trimIndent() + "\n"
            )
            File(project, "child").mkdirs()
            val child = File(project, "relocated-child").apply { mkdirs() }
            File(child, "build.gradle").writeText("plugins { id 'java-library';id 'maven-publish' };group='probe';version='1.0';publishing.publications { main(MavenPublication) { from components.java;artifactId='child' } }\n")
            File(child, "src/main/java/probe/Example.java").apply {
                parentFile.mkdirs()
                writeText("package probe; public class Example {}\n")
            }
            File(project, "build.gradle").appendText(
                """
                abstract class DescriptorProbe extends DefaultTask {
                    @Input abstract Property<String> getDescriptionValue()
                    @Input abstract Property<String> getGroupValue()
                    @Input abstract Property<Boolean> getStagingValue()
                    @TaskAction void verify() {
                        if(descriptionValue.get()!='late.group:renamed-root:2-SNAPSHOT' || groupValue.get()!='late.group' || stagingValue.get()) throw new GradleException('SETTINGS_FREEZE_ROOT_DESCRIPTOR_DEFAULTS_MISMATCH')
                        logger.lifecycle('SETTINGS_FREEZE_DESCRIPTOR_DEFAULTS_OK')
                    }
                }
                def coordinator=extensions.getByType(io.github.gradlenexus.publishplugin.internal.modern.RootCoordinator)
                assert tasks.findByName('publishToTemporary')==null
                tasks.register('descriptorWitness',DescriptorProbe) {
                    descriptionValue.set(coordinator.repositoryDescription)
                    groupValue.set(coordinator.packageGroup)
                    stagingValue.set(coordinator.useStaging)
                }
                tasks.named('descriptorWitness').get()
                afterEvaluate { group='late.group';version='2-SNAPSHOT' }
                """.trimIndent() + "\n"
            )
            var before = 0
            ModernGradleBuild(project, false, runtime).storeAndReuse(":child:publishToProbe", "descriptorWitness") { result ->
                assertThat(result.output).contains("SETTINGS_FREEZE_DESCRIPTOR_DEFAULTS_OK")
                assertThat(result.tasks.none { it.path.contains("Temporary") }).isTrue()
                val delta = remote.requests.drop(before)
                assertThat(delta.none { it.first == "POST" }).isTrue()
                assertThat(delta.filter { it.first == "PUT" }).isNotEmpty().allMatch { it.second.startsWith("/direct/probe/child/") }
                val jar = File(child, "build/libs/child-1.0.jar")
                assertThat(remote.files["/direct/probe/child/1.0/child-1.0.jar"]).isEqualTo(jar.readBytes())
                java.util.jar.JarFile(jar).use { assertThat(it.getJarEntry("probe/Example.class")).isNotNull() }
                assertThat(File(project, "child/build")).doesNotExist()
                before = remote.requests.size
            }
        }
    }

    private fun directory() =
        File(ModernTestPaths.workspaceRoot, "production-settings-freeze/${UUID.randomUUID()}/consumer").apply { mkdirs() }
    private fun configure(project: File, remote: MemoryRepository) {
        remote.response = { method, path -> if (method == "GET" && path == "/witness") MemoryRepository.Response(200, data = "ok") else null }
        File(project, "settings.gradle").writeText(
            """
            buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
            apply plugin:'io.github.gradle-nexus.publish-plugin'
            rootProject.name='freeze-probe'
            nexusPublishing.repositories.create('probe')
            """.trimIndent() + "\n"
        )
        File(project, "build.gradle").writeText(
            """
            tasks.register('freezeWitness') { doLast { logger.lifecycle('SETTINGS_FREEZE_TASK_EXECUTED');new URL('${remote.endpoint}witness').text } }
            """.trimIndent() + "\n"
        )
    }
}
