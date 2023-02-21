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

package io.github.gradlenexus.publishplugin.e2e

import io.github.gradlenexus.publishplugin.BaseGradleTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

@Suppress("FunctionName")
class NexusPublishE2ETests : BaseGradleTest() {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["nexus-publish-e2e-minimal", "nexus-publish-e2e-multi-project"])
    fun `release project to real Sonatype Nexus`(projectName: String) {
        File("src/e2eTest/resources/$projectName").copyRecursively(projectDir)

        // when
        val buildResult = run("build")
        // then
        buildResult.assertSuccess { it.path.substringAfterLast(':').matches("build".toRegex()) }

        // when
        val result = run(
            "publishToSonatype",
            "closeAndReleaseSonatypeStagingRepository",
            "--info"
        )
        // then
        result.apply {
            assertSuccess { it.path.substringAfterLast(':').matches("publish.+PublicationToSonatypeRepository".toRegex()) }
            assertSuccess(":closeSonatypeStagingRepository")
            assertSuccess(":releaseSonatypeStagingRepository")
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["nexus-publish-e2e-minimal", "nexus-publish-e2e-multi-project"])
    fun `release project to real Sonatype Nexus in two executions`(projectName: String) {
        File("src/e2eTest/resources/$projectName").copyRecursively(projectDir)

        // when
        val buildResult = run("build")
        // then
        buildResult.assertSuccess { it.path.substringAfterLast(':').matches("build".toRegex()) }

        // when
        // Publish artifacts to staging repository, close it == prepare artifacts for the review
        val result = run(
            "publishToSonatype",
            "closeSonatypeStagingRepository",
            "--info"
        )

        result.apply {
            assertSuccess { it.path.substringAfterLast(':').matches("publish.+PublicationToSonatypeRepository".toRegex()) }
            assertSuccess(":closeSonatypeStagingRepository")
        }

        // Release artifacts after the review
        val closeResult = run(
            "releaseSonatypeStagingRepository"
        )

        // then
        closeResult.apply {
            assertSuccess(":releaseSonatypeStagingRepository")
        }
    }
}
