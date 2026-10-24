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
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File

/** All modern consumer executions go through this strict, deliberately non-configurable runner. */
internal class ModernGradleBuild private constructor(
    private val projectDir: File,
    private val injectPluginClasspath: Boolean,
    private val gradleVersion: String,
    private val credentialEnvironment: Map<String, String>,
    private val isolatedDependencyCache: Boolean
) {
    constructor(
        projectDir: File,
        injectPluginClasspath: Boolean = true,
        runtime: ModernRuntime = ModernRuntime.CURRENT,
        credentialEnvironment: Map<String, String> = emptyMap(),
        isolatedDependencyCache: Boolean = false
    ) : this(projectDir, injectPluginClasspath, runtime.version, credentialEnvironment, isolatedDependencyCache)

    fun run(vararg tasks: String): BuildResult =
        runner(tasks).build()

    fun runContinuing(vararg tasks: String): BuildResult =
        runner(tasks, continueOnFailure = true).build()

    fun runWithBuildCache(vararg tasks: String): BuildResult =
        runner(tasks, buildCache = true).build()

    fun runRecovery(vararg requests: StagingRecoveryTask): BuildResult =
        runner(requests.map { it.task }.toTypedArray(), recoveryIds = requests.map { it.repositoryId }).build()

    fun failRecoveryContinuingWithDiagnostic(diagnostic: String, vararg requests: StagingRecoveryTask): BuildResult {
        val result = runner(requests.map { it.task }.toTypedArray(), continueOnFailure = true, recoveryIds = requests.map { it.repositoryId }).buildAndFail()
        assertThat(result.output).contains(diagnostic)
            .doesNotContain("problem was found storing the configuration cache", "problems were found storing the configuration cache")
        return result
    }

    fun storeAndReuse(vararg tasks: String, verify: (BuildResult) -> Unit = {}): Pair<BuildResult, BuildResult> {
        val stored = run(*tasks)
        assertThat(stored.output).contains("Configuration cache entry stored.")
        verify(stored)
        val reused = run(*tasks)
        assertThat(reused.output)
            .contains("Reusing configuration cache.", "Configuration cache entry reused.")
        verify(reused)
        return stored to reused
    }

    fun failWithConfigurationCacheProblem(problem: String, vararg tasks: String): BuildResult {
        val result = runner(tasks).buildAndFail()
        assertThat(result.output)
            .contains("1 problem was found storing the configuration cache.", problem)
            .contains("Configuration cache entry discarded with 1 problem.")
        return result
    }

    fun failWithDiagnostic(diagnostic: String, vararg tasks: String): BuildResult {
        val result = runner(tasks).buildAndFail()
        assertThat(result.output).contains(diagnostic)
            .doesNotContain("problem was found storing the configuration cache", "problems were found storing the configuration cache")
        return result
    }

    fun failContinuingWithDiagnostic(diagnostic: String, vararg tasks: String): BuildResult {
        val result = runner(tasks, continueOnFailure = true).buildAndFail()
        assertThat(result.output).contains(diagnostic)
            .doesNotContain("problem was found storing the configuration cache", "problems were found storing the configuration cache")
        return result
    }

    fun failWithSerializationError(problem: String, vararg tasks: String): BuildResult {
        val result = runner(tasks).buildAndFail()
        assertThat(result.output).contains(
            "1 problem was found storing the configuration cache.",
            "Configuration cache entry discarded due to serialization error.",
            problem
        )
        return result
    }

    private fun runner(tasks: Array<out String>, continueOnFailure: Boolean = false, buildCache: Boolean = false, recoveryIds: List<String> = emptyList()): GradleRunner {
        require(tasks.isNotEmpty() && tasks.none { it.startsWith("-") }) {
            "Only task requests are accepted; strict modern Gradle flags cannot be overridden."
        }
        require(recoveryIds.isEmpty() || (recoveryIds.size == tasks.size && recoveryIds.all { it.matches(Regex("[A-Za-z0-9_-]+")) })) {
            "Recovery accepts only typed staging repository identifiers."
        }
        check(System.getProperty("java.specification.version") == "17") {
            "Modern consumer tests require their pinned Java 17 launcher."
        }
        require(credentialEnvironment.keys.all { it in setOf("BoundaryFixture_USERNAME", "BoundaryFixture_PASSWORD", "BundleFixture_PGP_KEY", "BundleFixture_PGP_PASSWORD", "ORG_GRADLE_PROJECT_probeUsername", "ORG_GRADLE_PROJECT_probePassword") }) {
            "Only the named test credential environment keys may be overridden."
        }
        val requests = tasks.mapIndexed { index, task ->
            listOf(task) + if (recoveryIds.isEmpty()) emptyList() else listOf("--staging-repository-id=${recoveryIds[index]}")
        }.flatten()
        val resolutionHome = File(projectDir.parentFile, "resolution-home/$gradleVersion")
        val arguments = requests + (if (isolatedDependencyCache) listOf("--gradle-user-home", resolutionHome.absolutePath) else emptyList()) + (if (continueOnFailure) listOf("--continue") else emptyList()) + (if (buildCache) listOf("--build-cache") else emptyList()) + listOf(
            "--configuration-cache",
            "--parallel",
            "--max-workers=4",
            "--configuration-cache-problems=fail",
            "-Dorg.gradle.isolated-projects=true",
            "-Dorg.gradle.unsafe.isolated-projects=true",
            "-Dorg.gradle.isolated-projects.diagnostics=false",
            "-Dorg.gradle.unsafe.isolated-projects.diagnostics=false",
            "-Dorg.gradle.isolated-projects.dangerously-ignore-problems=false",
            "-Dorg.gradle.unsafe.isolated-projects.dangerously-ignore-problems=false",
            "-Porg.gradle.java.installations.auto-download=false",
            "--stacktrace"
        )
        println("Modern consumer: Gradle $gradleVersion, Java ${System.getProperty("java.version")}; $arguments")
        return GradleRunner.create()
            .apply { if (injectPluginClasspath) withPluginClasspath() }
            .withGradleVersion(gradleVersion)
            .withProjectDir(projectDir)
            // Keep the TestKit daemon registry shared; fresh resolution homes are selected separately above.
            .withTestKitDir(File(ModernTestPaths.workspaceRoot, "test-kit/$gradleVersion"))
            .withEnvironment(System.getenv().filterKeys { it != "CI" } + credentialEnvironment)
            .withArguments(arguments)
            .forwardOutput()
    }
}

internal data class StagingRecoveryTask(val task: String, val repositoryId: String)

internal enum class ModernRuntime(val version: String) {
    EARLIEST_CANDIDATE("9.7.1"),
    CURRENT(System.getProperty("modernTest.gradleVersion"))
}

internal fun BuildResult.assertExecutedTasks(vararg paths: String) {
    assertThat(tasks.map { it.path }).containsExactly(*paths)
    assertThat(tasks.map { it.outcome }).containsOnly(TaskOutcome.SUCCESS)
}
