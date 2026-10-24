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

package io.github.gradlenexus.publishplugin.internal.modern.bundle

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.w3c.dom.Document
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.TreeMap
import javax.xml.parsers.DocumentBuilderFactory

/**
 * JSON bundle v1; independent of Gradle models, task types and backend endpoints.
 */
object BundleCodec {
    private val JSON: Gson = GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create()

    @JvmStatic
    fun namespace(identity: String): String {
        try {
            val hash: ByteArray = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
            val hex: StringBuilder = StringBuilder("p-")
            for (item in hash) hex.append(String.format("%02x", item.toInt() and 255))
            return hex.toString()
        } catch (impossible: NoSuchAlgorithmException) {
            throw AssertionError(impossible)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun digest(file: Path): String {
        try {
            val hash: MessageDigest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(65536)
                var count: Int
                while ((input.read(buffer).also { count = it }) != -1) hash.update(buffer, 0, count)
            }
            val hex: StringBuilder = StringBuilder()
            for (item in hash.digest()) hex.append(String.format("%02x", item.toInt() and 255))
            return hex.toString()
        } catch (impossible: NoSuchAlgorithmException) {
            throw AssertionError(impossible)
        }
    }

    @JvmStatic
    private fun invalid(reason: String): IllegalArgumentException =
        IllegalArgumentException("NEXUS_INVALID_BUNDLE: $reason")

    @JvmStatic
    private fun canonical(path: String?): String {
        if (path.isNullOrEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":") || path.matches("^[a-zA-Z]:.*".toRegex())) throw invalid("unsafe path")
        for (segment in path.split("/".toRegex()).toTypedArray()) if (segment.isEmpty() || segment == "." || segment == "..") throw invalid("unsafe path")
        return path
    }

    @JvmStatic
    private fun fields(`object`: JsonObject, required: String, optional: String) {
        val allowed: MutableSet<String> = HashSet<String>(
            listOf(
                *("$required,$optional").split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            )
        )
        for ((key) in `object`.entrySet()) if (!allowed.contains(key)) throw invalid("unknown field")
        for (field in required.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) if (!`object`.has(field) || `object`.get(field).isJsonNull) throw invalid("missing field")
    }

    @JvmStatic
    @Throws(IOException::class)
    private fun read(directory: Path): JsonObject {
        val root: JsonObject
        val manifest: Path = ownedPayloadPath(directory, "manifest.json")
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) throw invalid("missing manifest")
        Files.newBufferedReader(manifest, StandardCharsets.UTF_8).use { reader ->
            root = JsonParser().parse(reader).getAsJsonObject()
        }
        fields(root, "schemaVersion,producerPath,format,empty,publications", "")
        if (root.get("schemaVersion").asString != "1") throw invalid("unsupported schema")
        val format: String = root.get("format").asString
        if (format != "maven" && format != "ivy") throw invalid("unsupported format")
        val publications: JsonArray = root.getAsJsonArray("publications")
        if (root.get("empty").asBoolean != (publications.size() == 0)) throw invalid("empty marker")
        val identities: MutableSet<String> = HashSet<String>()
        val destinations: MutableSet<String> = HashSet<String>()
        val payloads: MutableSet<String> = HashSet<String>()

        for (value in publications) {
            val publication: JsonObject = value.getAsJsonObject()
            fields(publication, "name,group,module,version,snapshot,kind,artifacts,entries,localRepositoryMetadata", "")
            if (!publication.get("snapshot").isJsonPrimitive || !publication.getAsJsonPrimitive("snapshot").isBoolean) throw invalid("invalid snapshot marker")
            for (artifact in publication.getAsJsonArray("artifacts")) fields(artifact.getAsJsonObject(), "name,classifier,extension", "type,conf")
            if (!identities.add(publication.get("name").asString)) throw invalid("duplicate publication")
            val publicationEntries: MutableMap<String, JsonObject> = LinkedHashMap<String, JsonObject>()
            val references: MutableList<String> = ArrayList<String>()
            for (item in publication.getAsJsonArray("entries")) {
                val entry: JsonObject = item.getAsJsonObject()
                fields(entry, "payload,destination,role,size,sha256", "name,classifier,extension,type,conf,relatedTo,describes")
                val role: String = entry.get("role").asString
                if (!listOf("artifact", "pom", "ivy-descriptor", "gradle-module", "signature", "checksum").contains(role)) throw invalid("unsupported role")
                if (role == "artifact") for (field in listOf("name", "classifier", "extension")) if (!entry.has(field) || entry.get(field).isJsonNull) throw invalid("missing artifact identity")
                if ((role == "signature" || role == "checksum") && !entry.has("relatedTo")) throw invalid("missing relationship target")
                if (role == "signature" && (!entry.has("extension") || entry.get("extension").isJsonNull || !entry.has("type") || entry.get("type").isJsonNull)) throw invalid("missing signature identity")
                if (format == "ivy" && role == "artifact" && (!entry.has("type") || entry.get("type").isJsonNull)) throw invalid("missing Ivy artifact identity")
                if ((role == "pom" || role == "ivy-descriptor" || role == "gradle-module") && !entry.has("describes")) throw invalid("missing metadata relationships")
                validatePayload(directory, entry, payloads)
                val destination: String = canonical(entry.get("destination").asString)
                if (!destinations.add(destination)) throw invalid("duplicate destination")
                publicationEntries[destination] = entry
                if (entry.has("relatedTo")) references.add(canonical(entry.get("relatedTo").asString))
                if (entry.has("describes")) {
                    for (reference in entry.getAsJsonArray("describes")) {
                        references.add(
                            canonical(reference.asString)
                        )
                    }
                }
            }
            if (!publicationEntries.keys.containsAll(references)) throw invalid("missing relationship target")
            for (entry in publicationEntries.values) {
                val role: String = entry.get("role").asString
                if (role == "signature" || role == "checksum") {
                    val targetRole: String = publicationEntries.getValue(entry.get("relatedTo").asString).get("role").asString
                    if (targetRole == "checksum" || (role == "signature" && targetRole == "signature")) throw invalid("invalid sidecar relationship")
                }
            }
            for (entry in publication.getAsJsonArray("localRepositoryMetadata")) {
                val local: JsonObject = entry.getAsJsonObject()
                fields(local, "payload,role,size,sha256,authoritative", "")
                if ((local.get("role").asString != "local-repository-metadata") || !local.get("authoritative").isJsonPrimitive || !local.get("authoritative").getAsJsonPrimitive().isBoolean || local.get("authoritative").asBoolean) throw invalid("invalid local metadata policy")
                validatePayload(directory, local, payloads)
            }
        }
        return root
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readModel(directory: Path): Map<String, Any> {
        try {
            return freeze(JSON.fromJson(read(directory), Map::class.java)) as Map<String, Any>
        } catch (malformed: RuntimeException) {
            if (malformed is IllegalArgumentException && malformed.message != null && malformed.message!!.startsWith("NEXUS_INVALID_BUNDLE: ")) throw malformed
            throw invalid("malformed manifest")
        }
    }

    @JvmStatic
    private fun freeze(value: Any?): Any? {
        if (value is MutableMap<*, *>) {
            val immutable: MutableMap<String, Any?> = LinkedHashMap<String, Any?>()
            value.forEach { (key: Any?, item: Any?) -> immutable[key.toString()] = freeze(item) }
            return Collections.unmodifiableMap<String, Any?>(immutable)
        }
        if (value is MutableList<*>) {
            val immutable: MutableList<Any?> = ArrayList<Any?>()
            for (item in value) immutable.add(freeze(item))
            return Collections.unmodifiableList<Any?>(immutable)
        }
        return value
    }

    @JvmStatic
    @Throws(IOException::class)
    private fun validatePayload(directory: Path, entry: JsonObject, payloads: MutableSet<String>) {
        val relative: String = canonical(entry.get("payload").asString)
        if (!payloads.add(relative)) throw invalid("duplicate payload")
        val file: Path = ownedPayloadPath(directory, relative)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw invalid("missing payload")
        if (Files.size(file) != entry.get("size").asLong) throw invalid("size mismatch")
        if (digest(file) != entry.get("sha256").asString) throw invalid("digest mismatch")
    }

    /**
     * Resolves only owned paths; inspect ancestors before dereferencing any payload file.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun ownedPayloadPath(directory: Path, relative: String): Path {
        canonical(relative)
        val root: Path = directory.toRealPath()
        var current: Path = root
        for (segment in relative.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) throw invalid("symlink payload")
        }
        if (!current.normalize().startsWith(root)) throw invalid("unsafe path")
        return current
    }

    @JvmStatic
    @Throws(Exception::class)
    fun write(directory: Path, producer: String, format: String, specifications: MutableMap<String, String>) {
        val root = JsonObject()
        root.addProperty("schemaVersion", 1)
        root.addProperty("producerPath", producer)
        root.addProperty("format", format)
        root.addProperty("empty", specifications.isEmpty())
        val publications = JsonArray()
        root.add("publications", publications)
        for ((_, value1) in TreeMap(specifications)) {
            val publication: JsonObject = JsonParser().parse(value1).getAsJsonObject()
            val signatureExtensions: JsonArray = publication.remove("nativeSignatureExtensions").getAsJsonArray()
            val name: String = publication.get("name").asString
            val repository: Path = directory.resolve("payload").resolve(namespace(name))
            val entries = JsonArray()
            val local = JsonArray()
            publication.add("entries", entries)
            publication.add("localRepositoryMetadata", local)
            val module: String = publication.get("module").asString
            val version: String = publication.get("version").asString
            var snapshotPrefix: String? = null
            if (format == "maven" && version.endsWith("-SNAPSHOT")) {
                Files.walk(repository).use { files ->
                    for (metadata in Iterable { files.filter { file: Path -> file.fileName.toString() == "maven-metadata.xml" }.iterator() }) {
                        ownedPayloadPath(directory, directory.relativize(metadata).toString().replace(File.separatorChar, '/'))
                        val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
                        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                        val document: Document = factory.newDocumentBuilder().parse(metadata.toFile())
                        if (document.getElementsByTagName("timestamp").length > 0) {
                            snapshotPrefix = module + "-" + version.substring(0, version.length - 9) + "-" + document.getElementsByTagName("timestamp").item(0).textContent + "-" + document.getElementsByTagName("buildNumber").item(0).textContent
                        }
                    }
                }
            }
            val files: List<Path>
            Files.walk(repository).use { stream ->
                files = stream.filter { path: Path -> Files.isRegularFile(path) }.sorted().toList()
            }
            val artifactDestinations = JsonArray()
            for (file in files) {
                val relative: String = repository.relativize(file).toString().replace(File.separatorChar, '/')
                ownedPayloadPath(directory, "payload/" + namespace(name) + "/" + relative)
                val entry = JsonObject()
                entry.addProperty("payload", "payload/" + namespace(name) + "/" + relative)
                entry.addProperty("size", Files.size(file))
                entry.addProperty("sha256", digest(file))
                if (file.fileName.toString().matches("maven-metadata\\.xml(\\.(md5|sha1|sha256|sha512))?".toRegex())) {
                    entry.addProperty("role", "local-repository-metadata")
                    entry.addProperty("authoritative", false)
                    local.add(entry)
                    continue
                }
                val slash: Int = relative.lastIndexOf('/')
                val filenamePart: String = relative.substring(slash + 1)
                val logicalFilenamePart: String =
                    if (snapshotPrefix != null && filenamePart.startsWith(snapshotPrefix as String)) {
                        module + "-" + version + filenamePart.substring((snapshotPrefix as String).length)
                    } else {
                        filenamePart
                    }
                val destination: String = relative.substring(0, slash + 1) + logicalFilenamePart
                entry.addProperty("destination", destination)
                val filename: String = file.fileName.toString()
                var declaredIdentity: JsonObject? = null
                for (declared in publication.getAsJsonArray("artifacts")) {
                    val identity: JsonObject = declared.getAsJsonObject()
                    val classifier: String = identity.get("classifier").asString
                    val artifactName: String = identity.get("name").asString
                    val expectedFilename: String = artifactName + "-" + version + (if (classifier.isEmpty()) "" else "-$classifier") + (if (format == "ivy" && identity.get("extension").asString.isEmpty()) "" else "." + identity.get("extension").asString)
                    val expectedIvy: String = publication.get("group").asString.replace('.', '/') + "/" + module + "/" + version + "/" + (if (identity.has("type")) identity.get("type").asString else "") + "/" + expectedFilename
                    if ((if (format == "ivy") (relative == expectedIvy) else (logicalFilenamePart == expectedFilename))) {
                        declaredIdentity = identity
                        break
                    }
                }
                var signatureExtension: String? = null
                for (extension in signatureExtensions) if (filename.endsWith("." + extension.asString)) signatureExtension = extension.asString
                val role: String
                if (declaredIdentity != null) {
                    role = "artifact"
                } else if (filename.matches(".*\\.(md5|sha1|sha256|sha512)$".toRegex())) {
                    role = "checksum"
                    entry.addProperty("relatedTo", destination.substring(0, destination.lastIndexOf('.')))
                } else if (signatureExtension != null) {
                    role = "signature"
                    if (format != "ivy") entry.addProperty("relatedTo", destination.substring(0, destination.length - signatureExtension.length - 1))
                    entry.addProperty("extension", signatureExtension)
                    entry.addProperty("type", signatureExtension)
                } else if (format == "ivy") {
                    val base: String = publication.get("group").asString.replace('.', '/') + "/" + module + "/" + version + "/"
                    role = when (relative) {
                        base + "ivy/ivy-" + version + ".xml" -> {
                            "ivy-descriptor"
                        }

                        base + "json/" + module + "-" + version + ".module" -> {
                            "gradle-module"
                        }

                        else -> {
                            throw invalid("unclassified native Ivy artifact")
                        }
                    }
                } else if (filename.endsWith(".pom")) {
                    role = "pom"
                } else if (filename.endsWith(".module")) {
                    role = "gradle-module"
                } else {
                    role = "artifact"
                }
                entry.addProperty("role", role)
                if (role == "artifact") {
                    artifactDestinations.add(destination)
                    if (declaredIdentity == null) throw invalid("unclassified native artifact")
                    entry.addProperty("name", declaredIdentity.get("name").asString)
                    entry.addProperty("classifier", declaredIdentity.get("classifier").asString)
                    entry.addProperty("extension", declaredIdentity.get("extension").asString)
                    if (declaredIdentity.has("type")) entry.add("type", declaredIdentity.get("type"))
                    if (declaredIdentity.has("conf") && !declaredIdentity.get("conf").isJsonNull) entry.add("conf", declaredIdentity.get("conf"))
                }
                entries.add(entry)
            }
            for (entry in entries) {
                val `object`: JsonObject = entry.getAsJsonObject()
                val role: String = `object`.get("role").asString
                if (format == "ivy" && role == "signature") {
                    val filename: String = Path.of(`object`.get("destination").asString).fileName.toString()
                    val suffix: String = "." + `object`.get("extension").asString
                    var target: JsonObject? = null
                    for (candidate in entries) {
                        val value: JsonObject = candidate.getAsJsonObject()
                        val candidateRole: String = value.get("role").asString
                        val targetFilename: String = Path.of(value.get("destination").asString).fileName.toString()
                        val emptyExtension: Boolean = candidateRole == "artifact" && value.get("extension").asString.isEmpty()
                        if ((candidateRole != "signature") && (candidateRole != "checksum") && filename == targetFilename + (if (emptyExtension) "." else "") + suffix) {
                            if (target != null) throw invalid("ambiguous native Ivy signature target")
                            target = value
                        }
                    }
                    if (target == null) throw invalid("missing native Ivy signature target")
                    `object`.addProperty("relatedTo", target.get("destination").asString)
                }
                if (role == "pom" || role == "ivy-descriptor" || role == "gradle-module") `object`.add("describes", artifactDestinations)
            }
            publications.add(publication)
        }
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("manifest.json"), JSON.toJson(root) + "\n", StandardCharsets.UTF_8)
        read(directory)
    }

    @JvmStatic
    fun publication(name: String, group: String, module: String, version: String, artifacts: MutableList<MutableMap<String, String?>>, signatureExtensions: MutableSet<String>): String {
        val value = JsonObject()
        value.addProperty("name", name)
        value.addProperty("group", group)
        value.addProperty("module", module)
        value.addProperty("version", version)
        value.addProperty("snapshot", version.endsWith("-SNAPSHOT"))
        value.addProperty("kind", if (artifacts.isEmpty()) "metadata-only" else "publication")
        value.add("nativeSignatureExtensions", JSON.toJsonTree(signatureExtensions))
        value.add("artifacts", JSON.toJsonTree(artifacts))
        return JSON.toJson(value)
    }
}
