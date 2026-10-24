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

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.gradlenexus.publishplugin.internal.modern.bundle.BundleCodec
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ArrayList
import java.util.HashSet
import java.util.HexFormat
import java.util.LinkedHashSet
import java.util.TreeMap

/**
 * Root-owned files; credentials and live models never enter these records.
 */
object WorkflowFiles {
    private val JSON: Gson = GsonBuilder().create()

    @JvmStatic
    @Throws(Exception::class)
    fun requireToken(workspace: Path, invocation: String) {
        check(Files.isRegularFile(BundleCodec.ownedPayloadPath(workspace, "invocation.txt"), LinkOption.NOFOLLOW_LINKS) && Files.readString(workspace.resolve("invocation.txt")) == invocation) { "NEXUS_WORKFLOW_NO_CURRENT_RESET" }
    }

    @JvmStatic
    fun payloads(model: Map<String, Any>): Set<String> {
        val paths: MutableSet<String> = LinkedHashSet<String>()
        paths.add("manifest.json")
        for (publication in model["publications"] as List<Map<String, Any>>) {
            for (field in listOf("entries", "localRepositoryMetadata")) {
                for (entry in publication[field] as List<Map<String, Any>>) {
                    paths.add(entry["payload"].toString())
                }
            }
        }
        return paths
    }

    @JvmStatic
    @Throws(Exception::class)
    fun fingerprints(staged: Path, units: List<String>): Map<String, String> {
        val values: MutableMap<String, String> = TreeMap<String, String>()
        for (unit in units) {
            val directory: Path = staged.resolve(unit)
            for (relative in payloads(BundleCodec.readModel(directory))) {
                values["$unit/$relative"] = BundleCodec.digest(BundleCodec.ownedPayloadPath(directory, relative))
            }
        }
        return values
    }

    @JvmStatic
    @Throws(Exception::class)
    fun write(path: Path, value: Record) {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
            JSON.toJson(value, writer)
        }
    }

    @JvmStatic
    fun read(path: Path, invocation: String?, repository: String?): Record =
        readPhase(path, invocation, repository)

    @JvmStatic
    fun readPhase(path: Path, invocation: String?, repository: String?, vararg phaseFields: String): Record {
        try {
            Files.newBufferedReader(BundleCodec.ownedPayloadPath(path.parent, path.fileName.toString()), StandardCharsets.UTF_8).use { reader ->
                val json: JsonObject = JsonParser().parse(reader).getAsJsonObject()
                for (field in listOf("schema", "invocation", "repository", "operation", "units", "fingerprints", "empty", "format", "route", "layoutPolicy", "endpointIdentity")) require(json.has(field) && !json.get(field).isJsonNull)
                require(json.get("schema").getAsJsonPrimitive().isNumber && json.get("schema").asString == "1" && json.get("empty").getAsJsonPrimitive().isBoolean)
                for (field in phaseFields) {
                    require(json.has(field) && !json.get(field).isJsonNull)
                    when (field) {
                        "modes" -> {
                            require(json.get(field).isJsonArray)
                            for (mode in json.getAsJsonArray(field)) require(mode.isJsonPrimitive && mode.getAsJsonPrimitive().isString)
                        }

                        "suppressIvyModuleMetadata" -> {
                            require(json.get(field).isJsonPrimitive && json.get(field).getAsJsonPrimitive().isBoolean)
                        }

                        else -> {
                            require(json.get(field).isJsonPrimitive && json.get(field).getAsJsonPrimitive().isString)
                        }
                    }
                }
                val value: Record = JSON.fromJson(json, Record::class.java)
                require(invocation == value.invocation && repository == value.repository && value.fingerprints != null && value.units != null && HashSet(value.units).size == value.units.size)
                for (unit in value.units) require(unit.matches("p[0-9]+".toRegex()))
                return value
            }
        } catch (_: Exception) {
            throw IllegalStateException("NEXUS_WORKFLOW_INVALID_CURRENT_RECEIPT")
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun identity(vararg values: String): String {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        for (value in values) {
            val bytes: ByteArray = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    class Record {
        @JvmField
        var schema: Int = 1

        @JvmField
        var invocation: String? = null

        @JvmField
        var repository: String? = null

        @JvmField
        var operation: String? = null

        @JvmField
        var endpointIdentity: String? = null

        @JvmField
        var destination: String? = null

        @JvmField
        var planBinding: String? = null

        @JvmField
        var format: String? = null

        @JvmField
        var route: String? = null

        @JvmField
        var layoutPolicy: String? = null

        @JvmField
        var units: MutableList<String> = ArrayList<String>()

        @JvmField
        var modes: MutableList<String> = ArrayList<String>()

        @JvmField
        var resumeId: String = ""

        @JvmField
        var sessionPolicyIdentity: String? = null

        @JvmField
        var backend: String? = null

        @JvmField
        var repositoryId: String? = null

        @JvmField
        var queryIdentity: String? = ""

        @JvmField
        var fingerprints: Map<String, String> = TreeMap<String, String>()

        @JvmField
        var empty: Boolean = false

        @JvmField
        var suppressIvyModuleMetadata: Boolean? = null
    }
}
