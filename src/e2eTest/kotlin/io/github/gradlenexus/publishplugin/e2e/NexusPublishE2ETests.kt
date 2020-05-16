/*
 * Copyright 2019 the original author or authors.
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

@Suppress("FunctionName")
class NexusPublishE2ETests : BaseGradleTest() {

    private var extraParams: Array<String> = arrayOf()

    @BeforeEach
    internal fun setup() {
        File("src//e2eTest//resources//nexus-publish-e2e-minimal").copyRecursively(projectDir.toFile())
    }

    @Test
    fun `release minimal project to real Sonatype Nexus`() {
        val result = run("publishToSonatype", "closeSonatypeStagingRepository", "releaseSonatypeStagingRepository", "-i", "--console=verbose", *extraParams)
        assertSuccess(result, ":publishToSonatype")
        assertSuccess(result, ":closeSonatypeStagingRepository")
        assertSuccess(result, ":releaseSonatypeStagingRepository")
    }
}
