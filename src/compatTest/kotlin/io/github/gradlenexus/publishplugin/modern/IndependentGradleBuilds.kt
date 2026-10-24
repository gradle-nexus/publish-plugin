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
import org.gradle.testkit.runner.BuildTask
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import java.io.File
import java.util.UUID

/** Independent build roots share an invocation; their models, workspaces and remote fixtures do not. */
internal class IndependentGradleBuilds(projects: List<File>, runtime: ModernRuntime, credentialEnvironment: Map<String, String> = emptyMap()) : java.io.Closeable {
    private val names = projects.mapIndexed { index, project -> project to "case$index" }.toMap()
    private val directory = File(ModernTestPaths.workspaceRoot, "independent-builds/${UUID.randomUUID()}").apply { mkdirs() }
    private val build = ModernGradleBuild(directory, false, runtime, credentialEnvironment)

    init {
        require(projects.isNotEmpty() && names.size == projects.size)
        File(directory, "settings.gradle").writeText(
            "rootProject.name='independent-cases'\n" + names.entries.joinToString("\n") { (project, name) ->
                "includeBuild('${project.invariantSeparatorsPath}') { name='$name' }"
            } + "\n"
        )
        File(directory, "build.gradle").writeText("")
    }

    fun run(requests: Map<File, List<String>>): Map<File, BuildResult> {
        val tasks = requests.flatMap { (project, paths) ->
            val name = names.getValue(project)
            require(paths.isNotEmpty() && paths.all { it.startsWith(":") && !it.contains(" ") })
            paths.map { ":$name$it" }
        }
        val result = try {
            build.runContinuing(*tasks.toTypedArray())
        } catch (failure: UnexpectedBuildFailure) {
            failure.buildResult
        }
        assertThat(result.output).doesNotContain("problem was found storing the configuration cache", "problems were found storing the configuration cache")
        return requests.mapValues { (project, _) ->
            val prefix = ":${names.getValue(project)}"
            val selected = result.tasks.filter { it.path.startsWith("$prefix:") }.map { task ->
                object : BuildTask {
                    override fun getPath(): String =
                        task.path.removePrefix(prefix)
                    override fun getOutcome(): TaskOutcome =
                        task.outcome
                }
            }
            assertThat(selected).describedAs("requested independent build ${names.getValue(project)} actually executed").isNotEmpty()
            ScopedResult(result.output, selected, prefix)
        }
    }

    override fun close() {
        val cache = File(directory, ".gradle/configuration-cache")
        check(!cache.exists() || cache.deleteRecursively()) { "Owned composite configuration cache could not be removed" }
        check(!cache.exists()) { "Owned composite configuration cache survived cleanup" }
    }

    private class ScopedResult(private val output: String, private val tasks: List<BuildTask>, val prefix: String) : BuildResult {
        override fun getOutput(): String =
            output
        override fun getTasks(): List<BuildTask> =
            tasks
        override fun tasks(outcome: TaskOutcome): List<BuildTask> =
            tasks.filter { it.outcome == outcome }
        override fun taskPaths(outcome: TaskOutcome): List<String> =
            tasks(outcome).map { it.path }
        override fun task(path: String): BuildTask? =
            tasks.firstOrNull { it.path == path }
    }

    companion object {
        /** Failure sections must name this included build; invocation-wide output is only for cache/secrecy checks. */
        fun failureOutput(result: BuildResult): String {
            val scoped = result as ScopedResult
            val starts = Regex("(?m)^\\d+: Task failed with an exception\\.").findAll(result.output).map { it.range.first }.toList()
            val sections = if (starts.isEmpty()) {
                listOf(result.output)
            } else {
                starts.mapIndexed { index, start ->
                    result.output.substring(start, if (index + 1 < starts.size) starts[index + 1] else result.output.length)
                }
            }
            return sections.filter { it.contains("'${scoped.prefix}:") }.joinToString("\n")
        }
    }
}
