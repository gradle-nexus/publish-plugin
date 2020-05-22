/*
 * Copyright 2020 the original author or authors.
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

import org.assertj.core.api.Assertions
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

abstract class BaseGradleTest {

    private val gradleRunner = GradleRunner.create()
            .withPluginClasspath()

    @TempDir
    protected lateinit var projectDir: Path

    protected fun run(vararg arguments: String): BuildResult {
        return gradleRunner(*arguments).build()
    }

    protected fun gradleRunner(vararg arguments: String): GradleRunner {
        return gradleRunner
//                .withDebug(true)
                .withProjectDir(projectDir.toFile())
                .withArguments(*arguments, "--stacktrace")
                .forwardOutput()
    }

    protected fun assertSuccess(result: BuildResult, taskPath: String) {
        assertOutcome(result, taskPath, TaskOutcome.SUCCESS)
    }

    protected fun assertOutcome(result: BuildResult, taskPath: String, outcome: TaskOutcome) {
        Assertions.assertThat(result.task(taskPath)).describedAs("Task $taskPath")
                .isNotNull
                .extracting { it!!.outcome }
                .isEqualTo(outcome)
    }
}
