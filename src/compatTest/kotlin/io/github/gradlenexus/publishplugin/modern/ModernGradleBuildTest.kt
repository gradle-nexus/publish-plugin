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
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ModernGradleBuildTest {

    @TempDir
    lateinit var workspace: File

    @Test
    fun `settings plugin probe stores and reuses with isolated projects enabled`() {
        val projectDir = fixture()
        File(projectDir, "settings.gradle").writeText(
            """
            import org.gradle.api.*
            import org.gradle.api.initialization.Settings
            import org.gradle.api.tasks.TaskAction

            assert providers.gradleProperty('org.gradle.java.installations.auto-download').get() == 'false'
            abstract class ProbeTask extends DefaultTask {
                @TaskAction void probe() { logger.lifecycle("modern-settings-probe") }
            }
            class ProbeSettingsPlugin implements Plugin<Settings> {
                void apply(Settings settings) {
                    settings.gradle.lifecycle.beforeProject { project ->
                        project.tasks.register("probe", ProbeTask)
                    }
                }
            }
            apply plugin: ProbeSettingsPlugin
            rootProject.name = "modern-probe"
            """.trimIndent()
        )

        val (stored, reused) = ModernGradleBuild(projectDir).storeAndReuse("probe")

        assertThat(stored.output).contains("Isolated Projects is an incubating feature.")
        listOf(stored, reused).forEach {
            it.assertExecutedTasks(":probe")
            assertThat(it.output).contains("modern-settings-probe")
        }
    }

    @Test
    fun `unsupported task project capture fails strict configuration cache`() {
        val projectDir = fixture()
        File(projectDir, "build.gradle").writeText(
            """
            abstract class UnsupportedTask extends DefaultTask {
                @TaskAction void capture() { println(project.name) }
            }
            tasks.register("unsupported", UnsupportedTask)
            """.trimIndent()
        )

        ModernGradleBuild(projectDir).failWithConfigurationCacheProblem(
            "invocation of 'Task.project' at execution time is unsupported",
            "unsupported"
        )
    }

    @Test
    fun `cross project configuration access fails isolated projects`() {
        val projectDir = fixture()
        File(projectDir, "settings.gradle").appendText("include 'child'\n")
        File(projectDir, "child").mkdirs()
        File(projectDir, "build.gradle").writeText("println(project(':child').version)\n")

        ModernGradleBuild(projectDir).failWithConfigurationCacheProblem(
            "cannot access 'Project.version' functionality on another project ':child'",
            "help"
        )
    }

    @Test
    fun `caller cannot override strict modern flags`() {
        val build = ModernGradleBuild(fixture())
        assertThatIllegalArgumentException().isThrownBy {
            build.run("help", "--configuration-cache-problems=warn")
        }
        assertThatIllegalArgumentException().isThrownBy {
            build.runWithBuildCache("help", "--configuration-cache-problems=warn")
        }
        assertThatIllegalArgumentException().isThrownBy {
            build.runRecovery(StagingRecoveryTask("--configuration-cache-problems=warn", "repo-1"))
        }
        assertThatIllegalArgumentException().isThrownBy {
            build.runRecovery(StagingRecoveryTask("help", "repo\n--configuration-cache-problems=warn"))
        }
    }

    @Test
    fun `caller cannot override runtime environment through credential map`() {
        val build = ModernGradleBuild(fixture(), credentialEnvironment = mapOf("JAVA_HOME" to "unapproved"))
        assertThatIllegalArgumentException().isThrownBy { build.run("help") }
    }

    private fun fixture(): File =
        File(workspace, "consumer").apply {
            mkdirs()
            File(this, "settings.gradle").writeText("rootProject.name = 'modern-probe'\n")
        }
}
