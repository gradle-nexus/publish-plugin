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

package io.github.gradlenexus.publishplugin.internal.modern.workflow

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Direct production protocol checks; runtime action admission remains in TestKit. */
@EnabledForJreRange(min = JRE.JAVA_17)
class WorkflowFilesTest {
    @Test
    fun `valid current root receipt loads`(@TempDir directory: File) {
        val record = read(write(directory, valid()))
        assertThat(record.invocation).isEqualTo("current")
        assertThat(record.units).isEqualTo(listOf("p0"))
    }

    @Test
    fun `schema must be explicitly present and exactly supported`(@TempDir directory: File) {
        listOf(
            valid().replace("\"schema\":1,", ""),
            valid().replace("\"schema\":1", "\"schema\":null"),
            valid().replace("\"schema\":1", "\"schema\":2"),
            valid().replace("\"schema\":1", "\"schema\":1.5"),
            valid().replace("\"schema\":1", "\"schema\":\"1\""),
            valid().replace("\"schema\":1", "\"schema\":true")
        ).forEach { reject(write(directory, it)) }
    }

    @Test
    fun `missing required fields and malformed records reject admission`(@TempDir directory: File) {
        val missing = listOf(
            "\"operation\":\"plan\",",
            "\"units\":[\"p0\"],",
            "\"fingerprints\":{},",
            "\"empty\":true,",
            "\"format\":\"maven\",",
            "\"route\":\"direct\",",
            "\"layoutPolicy\":\"maven-standard\","
        )
        missing.forEach { reject(write(directory, valid().replace(it, ""))) }
        listOf("", "{", "[]", "null", valid().replace("\"empty\":true", "\"empty\":\"true\""))
            .forEach { reject(write(directory, it)) }
    }

    @Test
    fun `wrong invocation or repository rejects admission`(@TempDir directory: File) {
        listOf(
            valid().replace("current", "previous"),
            valid().replace("\"repository\":\"probe\"", "\"repository\":\"other\"")
        ).forEach { reject(write(directory, it)) }
    }

    @Test
    fun `unsafe duplicate or null units reject admission`(@TempDir directory: File) {
        listOf(
            valid().replace("[\"p0\"]", "[\"p0\",\"p0\"]"),
            valid().replace("[\"p0\"]", "[\"../outside\"]"),
            valid().replace("[\"p0\"]", "[null]")
        ).forEach { reject(write(directory, it)) }
    }

    @Test
    fun `existing symbolic receipt is rejected without opening its target`() {
        val existing = Paths.get("/proc/self/root")
        assumeTrue(Files.isSymbolicLink(existing), "Existing Linux proc link is needed; this test creates no symlink")
        reject(existing)
    }

    @Test
    fun `token must exist and match current invocation`(@TempDir directory: File) {
        val token = File(directory, "invocation.txt")
        token.writeText("current")
        requireToken(directory.toPath(), "current")
        rejectToken(directory.toPath(), "previous")
        token.delete()
        rejectToken(directory.toPath(), "current")
    }

    @Test
    fun `explicit session phase fields load`(@TempDir directory: File) {
        val record = readPhase(write(directory, phaseRecord()))
        assertThat(record.modes).isEqualTo(listOf("find"))
        assertThat(record.backend).isEqualTo("nexus2")
        assertThat(record.repositoryId).isEqualTo("found-1")
        assertThat(record.suppressIvyModuleMetadata).isEqualTo(false)
        val suppressed = readPhase(write(directory, phaseRecord(mapOf("suppressIvyModuleMetadata" to "true"))))
        assertThat(suppressed.suppressIvyModuleMetadata).isEqualTo(true)
    }

    @Test
    fun `phase admission rejects absent fields instead of record defaults`(@TempDir directory: File) {
        phaseValues.keys.forEach { field ->
            rejectPhase(write(directory, phaseRecord(mapOf(field to null))))
            rejectPhase(write(directory, phaseRecord(mapOf(field to "null"))))
        }
    }

    @Test
    fun `phase fields require explicit strings boolean and string mode arrays`(@TempDir directory: File) {
        phaseValues.keys.filter { it !in setOf("modes", "suppressIvyModuleMetadata") }.forEach { field ->
            listOf("0", "false", "[]", "{}").forEach { value ->
                rejectPhase(write(directory, phaseRecord(mapOf(field to value))))
            }
        }
        listOf("\"false\"", "0", "[]", "{}").forEach { value ->
            rejectPhase(write(directory, phaseRecord(mapOf("suppressIvyModuleMetadata" to value))))
        }
        listOf("\"find\"", "{}", "[0]", "[false]", "[null]").forEach { value ->
            rejectPhase(write(directory, phaseRecord(mapOf("modes" to value))))
        }
    }

    private val phaseValues = linkedMapOf(
        "modes" to "[\"find\"]",
        "suppressIvyModuleMetadata" to "false",
        "resumeId" to "\"\"",
        "sessionPolicyIdentity" to "\"policy\"",
        "backend" to "\"nexus2\"",
        "repositoryId" to "\"found-1\"",
        "destination" to "\"https://repo.example.org/staging/deployByRepositoryId/found-1/\"",
        "planBinding" to "\"binding\"",
        "queryIdentity" to "\"query\""
    )

    private fun phaseRecord(overrides: Map<String, String?> = emptyMap()): String =
        valid().dropLast(1) + phaseValues.entries.joinToString("") { (field, default) ->
            val value = if (overrides.containsKey(field)) overrides[field] else default
            if (value == null) "" else ",\"$field\":$value"
        } + "}"

    private fun readPhase(path: Path): WorkflowFiles.Record =
        WorkflowFiles.readPhase(path, "current", "probe", *phaseValues.keys.toTypedArray())

    private fun rejectPhase(path: Path) {
        val error = catchThrowable { readPhase(path) }
        assertThat(error).isInstanceOf(Exception::class.java)
        assertThat(error.message).contains("NEXUS_WORKFLOW_INVALID_CURRENT_RECEIPT")
    }

    private fun read(path: Path): WorkflowFiles.Record =
        WorkflowFiles.read(path, "current", "probe")

    private fun requireToken(path: Path, invocation: String) {
        WorkflowFiles.requireToken(path, invocation)
    }

    private fun reject(path: Path) {
        val error = catchThrowable { read(path) }
        assertThat(error).isInstanceOf(Exception::class.java)
        assertThat(error.message).contains("NEXUS_WORKFLOW_INVALID_CURRENT_RECEIPT")
    }

    private fun rejectToken(path: Path, invocation: String) {
        val error = catchThrowable { requireToken(path, invocation) }
        assertThat(error).isInstanceOf(Exception::class.java)
        assertThat(error.message).startsWith("NEXUS_")
    }

    private fun write(directory: File, value: String): Path =
        File(directory, "record.json").apply { writeText(value) }.toPath()

    private fun valid() =
        """{"schema":1,"invocation":"current","repository":"probe","operation":"plan","units":["p0"],"fingerprints":{},"empty":true,"format":"maven","route":"direct","layoutPolicy":"maven-standard","endpointIdentity":""}"""
}
