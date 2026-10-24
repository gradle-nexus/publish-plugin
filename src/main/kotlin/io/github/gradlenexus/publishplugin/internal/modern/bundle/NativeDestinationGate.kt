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

import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import java.io.Serializable
import java.net.URI
import java.nio.file.Paths

/**
 * Actual task repository URI calculated at CC serialization, not a copy of the initial candidate.
 */
class NativeDestinationGate(private val actual: Provider<URI>, private val owned: URI) :
    Spec<Task>,
    Serializable {

    override fun isSatisfiedBy(ignored: Task): Boolean {
        val target: URI = actual.get()
        try {
            check(!(!"file".equals(target.scheme, ignoreCase = true) || Paths.get(target).toAbsolutePath().normalize() != Paths.get(owned).toAbsolutePath().normalize())) { "NEXUS_NATIVE_DESTINATION_REJECTED" }
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("NEXUS_NATIVE_DESTINATION_REJECTED")
        }
        return true
    }
}
