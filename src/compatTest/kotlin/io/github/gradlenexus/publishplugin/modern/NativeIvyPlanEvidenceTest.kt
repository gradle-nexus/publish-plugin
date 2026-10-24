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

import com.google.gson.Gson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** Real consuming-Gradle path evidence is reused only for an immutable generation/layout. */
internal class NativeIvyPlanEvidenceTest {
    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `evidence reuses identical inputs and separates changed generations and layouts`(runtime: ModernRuntime) {
        val bundle = File(ModernTestPaths.workspaceRoot, "native-evidence-input/${UUID.randomUUID()}").apply { mkdirs() }
        write(bundle, "abc")
        val first = plan(bundle.toPath(), "first/[artifact].[ext]", runtime)
        assertThat(first.keys).containsExactly("first/a.jar")
        assertThat(first.getValue("first/a.jar").file.readText()).isEqualTo("abc")
        assertThat(plan(bundle.toPath(), "first/[artifact].[ext]", runtime)).isSameAs(first)
        write(bundle, "abd")
        val changed = plan(bundle.toPath(), "first/[artifact].[ext]", runtime)
        assertThat(changed).isNotSameAs(first)
        assertThat(changed.getValue("first/a.jar").file.readText()).isEqualTo("abd")
        assertThat(first.getValue("first/a.jar").file.readText()).isEqualTo("abc")
        val layout = plan(bundle.toPath(), "second/[artifact].[ext]", runtime)
        assertThat(layout.keys).containsExactly("second/a.jar")
        assertThat(layout.getValue("second/a.jar").file.readText()).isEqualTo("abd")
        assertThat(plan(bundle.toPath(), "first/[artifact].[ext]", runtime)).isSameAs(changed)
    }

    private fun plan(bundle: Path, pattern: String, runtime: ModernRuntime) =
        NativeIvyPlanEvidence.plan(listOf(bundle), listOf(pattern), emptyList(), false, true, runtime)

    private fun write(bundle: File, value: String) {
        File(bundle, "payload").mkdirs()
        File(bundle, "payload/a.jar").writeText(value)
        val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val entry = mapOf("payload" to "payload/a.jar", "destination" to "g/a/1/a-1.jar", "role" to "artifact", "name" to "a", "type" to "jar", "extension" to "jar", "classifier" to "", "size" to value.length, "sha256" to hash)
        val publication = mapOf("name" to "p", "group" to "g", "module" to "a", "version" to "1", "snapshot" to false, "kind" to "publication", "artifacts" to emptyList<Any>(), "entries" to listOf(entry), "localRepositoryMetadata" to emptyList<Any>())
        val manifest = mapOf("schemaVersion" to 1, "producerPath" to ":p", "format" to "ivy", "empty" to false, "publications" to listOf(publication))
        File(bundle, "manifest.json").writeText(Gson().toJson(manifest))
    }
}
