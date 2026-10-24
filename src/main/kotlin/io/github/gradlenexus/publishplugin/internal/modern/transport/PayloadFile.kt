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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Immutable file identity; no credentials, endpoint, session or materialized publication bytes.
 */
class PayloadFile internal constructor(
    directory: Path,
    private val relativePath: String,
    private val size: Long,
    private val sha256: String
) {
    private val directory: Path = directory.toAbsolutePath().normalize()

    @Throws(IOException::class)
    fun getFile(): Path =
        BundleCodec.ownedPayloadPath(directory, relativePath)

    fun getSize(): Long =
        size

    fun getSha256(): String =
        sha256

    @Throws(IOException::class)
    fun verify() {
        val path: Path = getFile()
        require(!(Files.size(path) != size || BundleCodec.digest(path) != sha256)) { "NEXUS_DEPLOYMENT_PAYLOAD_CHANGED" }
    }
}
