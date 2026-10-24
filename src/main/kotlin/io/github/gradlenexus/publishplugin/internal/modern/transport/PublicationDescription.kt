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

package io.github.gradlenexus.publishplugin.internal.modern.transport

import io.github.gradlenexus.publishplugin.internal.modern.bundle.BundleCodec
import java.nio.file.Path
import java.util.Collections

/** Detached values only: no Gradle models, protocol clients, credentials or materialized bytes. */
internal data class ArtifactIdentity(val name: String, val type: String, val extension: String, val classifier: String, val conf: String?)

internal class PublicationArtifact(
    val destination: String,
    val role: String,
    val identity: ArtifactIdentity?,
    val payload: PayloadFile,
    val relatedTo: String?
)

internal class PublicationDescription(
    val name: String,
    val group: String,
    val module: String,
    val version: String,
    artifacts: List<PublicationArtifact>
) {
    val artifacts: List<PublicationArtifact> = Collections.unmodifiableList(ArrayList(artifacts))

    companion object {
        fun read(directories: List<Path>, format: String): List<PublicationDescription> =
            Collections.unmodifiableList(
                directories.flatMap { directory ->
                    fromModel(directory, format, BundleCodec.readModel(directory))
                }
            )

        /** Use only a model freshly verified by BundleCodec in the current action. */
        fun fromModel(directory: Path, format: String, manifest: Map<String, Any>): List<PublicationDescription> {
            require(manifest["format"] == format) { "NEXUS_EXPECTED_${format.toUpperCase(java.util.Locale.ROOT)}_BUNDLE" }
            return (manifest["publications"] as List<Map<String, Any>>).map { publication ->
                val entries = (publication["entries"] as List<Map<String, Any>>).associateBy { it.getValue("destination").toString() }
                PublicationDescription(
                    publication.getValue("name").toString(),
                    publication.getValue("group").toString(),
                    publication.getValue("module").toString(),
                    publication.getValue("version").toString(),
                    entries.values.map { entry ->
                        PublicationArtifact(
                            entry.getValue("destination").toString(),
                            entry.getValue("role").toString(),
                            if (entry["role"] == "checksum") null else identity(format, publication, entry, entries),
                            PayloadFile(directory, entry.getValue("payload").toString(), (entry.getValue("size") as Number).toLong(), entry.getValue("sha256").toString()),
                            entry["relatedTo"]?.toString()
                        )
                    }
                )
            }
        }

        /** One canonical conversion is shared by path/collision planning and native normalization. */
        private fun identity(format: String, publication: Map<String, Any>, entry: Map<String, Any>, entries: Map<String, Map<String, Any>>): ArtifactIdentity =
            when (entry["role"]) {
                "artifact" -> ArtifactIdentity(entry.getValue("name").toString(), entry["type"]?.toString() ?: entry.getValue("extension").toString(), entry.getValue("extension").toString(), entry.getValue("classifier").toString(), entry["conf"]?.toString())

                "pom" -> {
                    require(format == "maven") { "NEXUS_UNSUPPORTED_IVY_ROLE" }
                    ArtifactIdentity(publication.getValue("module").toString(), "pom", "pom", "", null)
                }

                "ivy-descriptor" -> {
                    require(format == "ivy") { "NEXUS_UNSUPPORTED_MAVEN_ROLE" }
                    ArtifactIdentity("ivy", "ivy", "xml", "", null)
                }

                "gradle-module" -> ArtifactIdentity(publication.getValue("module").toString(), "json", "module", "", null)

                "signature" -> {
                    val target = requireNotNull(entries[entry.getValue("relatedTo").toString()]) { "NEXUS_RELATIONSHIP_CROSSES_PUBLICATION" }
                    require(target["role"] !in listOf("signature", "checksum")) { "NEXUS_INVALID_SIGNATURE_RELATIONSHIP" }
                    val targetIdentity = identity(format, publication, target, entries)
                    targetIdentity.copy(type = entry.getValue("type").toString(), extension = targetIdentity.extension + "." + entry.getValue("extension"), conf = entry["conf"]?.toString())
                }

                else -> throw IllegalArgumentException("NEXUS_UNSUPPORTED_${format.toUpperCase(java.util.Locale.ROOT)}_ROLE")
            }
    }
}
