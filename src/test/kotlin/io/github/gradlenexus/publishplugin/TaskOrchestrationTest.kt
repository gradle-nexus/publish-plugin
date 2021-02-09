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
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.get
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path

@KotlinParameterizeTest
class TaskOrchestrationTest {

    @TempDir
    lateinit var projectDir: Path

    private lateinit var project: Project

    @BeforeEach
    internal fun setUp() {
        project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build()
    }

    @ParameterizedTest(name = "task: {0}")
    @MethodSource("transitioningTaskNamesForSonatype")
    internal fun `transitioning task should run after init`(transitioningTaskName: String) {
        initSingleProjectWithDefaultConfiguration()
        assertGivenTaskMustRunAfterAnother(transitioningTaskName, "initializeSonatypeStagingRepository")
    }

    @ParameterizedTest(name = "task: {0}")
    @MethodSource("transitioningTaskNamesForSonatype")
    internal fun `transitioning task should run after related publish`(transitioningTaskName: String) {
        initSingleProjectWithDefaultConfiguration()
        assertGivenTaskMustRunAfterAnother(transitioningTaskName, "publishToSonatype")
    }

    @ParameterizedTest(name = "task: {0}")
    @MethodSource("transitioningTaskNamesForSonatype")
    internal fun `transitioning task should not run after non-related publish`(transitioningTaskName: String) {
        //given
        initSingleProjectWithDefaultConfiguration()
        project.extensions.configure<NexusPublishExtension> {
            repositories.add(NexusRepository("myNexus", project))
        }
        //expect
        assertGivenTaskMustNotRunAfterAnother(transitioningTaskName, "publishToMyNexus")
    }

    @Test
    @Disabled("TODO")
    internal fun `close task should run after all related publish tasks in multi-project build`() {}

    @Test
    internal fun `release task should run after close`() {
        initSingleProjectWithDefaultConfiguration()
        assertGivenTaskMustRunAfterAnother("releaseSonatypeStagingRepository", "closeSonatypeStagingRepository")
    }

    @Test
    internal fun `closeAndRelease for given repository should depend on close and release tasks`() {
        initSingleProjectWithDefaultConfiguration()

        val closeAndReleaseTask = getJustOneTaskByNameOrFail("closeAndReleaseSonatypeStagingRepository")

        assertThat(closeAndReleaseTask.taskDependencies.getDependencies(null).map { it.name })
                .contains("closeSonatypeStagingRepository", "releaseSonatypeStagingRepository")
    }

    @Test
    internal fun `simplified close and release task without repository name should be available but trigger nothing if no repositories are configured`() {
        initSingleProjectWithDefaultConfiguration()
        project.extensions.configure<NexusPublishExtension> {
            repositories.clear()
        }

        val simplifiedCloseAndReleaseTasks = project.getTasksByName(NexusPublishPlugin.SIMPLIFIED_CLOSE_AND_RELEASE_TASK_NAME, true)

        assertThat(simplifiedCloseAndReleaseTasks).hasSize(1)
        assertThat(simplifiedCloseAndReleaseTasks.first().dependsOn).isEmpty()
    }

    @Test
    internal fun `simplified closeAndRelease task without repository name should depend on all closeAndRelease (created one per defined repository)`() {
        initSingleProjectWithDefaultConfiguration()
        project.extensions.configure<NexusPublishExtension> {
            repositories.create("otherNexus")
        }

        val closeAndReleaseTask = getJustOneTaskByNameOrFail(NexusPublishPlugin.SIMPLIFIED_CLOSE_AND_RELEASE_TASK_NAME)

        assertThat(closeAndReleaseTask.taskDependencies.getDependencies(null).map { it.name })
                .hasSize(2)
                .contains("closeAndReleaseSonatypeStagingRepository")
                .contains("closeAndReleaseOtherNexusStagingRepository")
    }

    @Test
    internal fun `description of simplified closeAndRelease task contains names of all defined Nexus instances`() {
        initSingleProjectWithDefaultConfiguration()
        project.extensions.configure<NexusPublishExtension> {
            repositories.create("otherNexus")
        }

        val closeAndReleaseTask = getJustOneTaskByNameOrFail(NexusPublishPlugin.SIMPLIFIED_CLOSE_AND_RELEASE_TASK_NAME)

        assertThat(closeAndReleaseTask.description)
                .contains("sonatype")
                .contains("otherNexus")
    }

    private fun initSingleProjectWithDefaultConfiguration() {
        project.apply(plugin = "java")
        project.apply(plugin = "maven-publish")
        project.apply<NexusPublishPlugin>()
        project.extensions.configure<NexusPublishExtension> {
            repositories.sonatype()
        }
        project.extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("mavenJava") {
                from(project.components["java"])
            }
        }
    }

    private fun assertGivenTaskMustRunAfterAnother(taskName: String, expectedPredecessorName: String) {
        val task = getJustOneTaskByNameOrFail(taskName)
        val expectedPredecessor = getJustOneTaskByNameOrFail(expectedPredecessorName)
        assertThat(task.mustRunAfter.getDependencies(task)).contains(expectedPredecessor)
    }

    private fun assertGivenTaskMustNotRunAfterAnother(taskName: String, notExpectedPredecessorName: String) {
        val task = getJustOneTaskByNameOrFail(taskName)
        val notExpectedPredecessor = getJustOneTaskByNameOrFail(notExpectedPredecessorName)
        assertThat(task.mustRunAfter.getDependencies(task)).doesNotContain(notExpectedPredecessor)
    }

    private fun getJustOneTaskByNameOrFail(taskName: String): Task {
        val tasks = project.getTasksByName(taskName, true) // forces project evaluation
        assertThat(tasks.size).describedAs("Expected just one task: $taskName. Found: ${project.tasks.names}").isOne()
        return tasks.first()
    }

    @Suppress("unused")
    private fun transitioningTaskNamesForSonatype(): List<String> = listOf("closeSonatypeStagingRepository", "releaseSonatypeStagingRepository")
}
