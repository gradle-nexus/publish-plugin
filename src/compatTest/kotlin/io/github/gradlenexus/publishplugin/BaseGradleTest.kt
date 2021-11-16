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

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.BuildTask
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File

abstract class BaseGradleTest {

    private val gradleRunner = GradleRunner.create()
        .withPluginClasspath()

    @TempDir
    protected lateinit var projectDir: File

    protected fun run(vararg arguments: String): BuildResult {
        return gradleRunner(*arguments).build()
    }

    protected fun gradleRunner(vararg arguments: String): GradleRunner {
        return gradleRunner
//                .withDebug(true)
            .withProjectDir(projectDir)
            .withArguments(*arguments, "--stacktrace")
            .forwardOutput()
    }

    protected fun BuildResult.assertSuccess(taskPath: String) {
        assertSuccess { it.path == taskPath }
    }

    protected fun BuildResult.assertSuccess(taskPredicate: (BuildTask) -> Boolean) {
        assertOutcome(taskPredicate, TaskOutcome.SUCCESS)
    }

    protected fun BuildResult.assertOutcome(taskPredicate: (BuildTask) -> Boolean, outcome: TaskOutcome) {
        val tasks = tasks.filter(taskPredicate)
        assertThat(tasks).hasSizeGreaterThanOrEqualTo(1)
        assertThat(tasks.map { it.outcome }.distinct()).describedAs(tasks.toString()).containsExactly(outcome)
    }
}
