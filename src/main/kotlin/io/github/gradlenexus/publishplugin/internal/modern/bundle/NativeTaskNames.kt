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

import io.github.gradlenexus.publishplugin.internal.modern.BootstrapSpec

/**
 * Pure shared naming for producer exports and root manifest-derived admission.
 */
object NativeTaskNames {
    @JvmStatic
    fun exportName(format: String, publicationName: String): String =
        exportPrefix(format) + BootstrapSpec.capitalize(publicationName)

    @JvmStatic
    fun exportNamespace(producerPath: String, format: String): String =
        (if (producerPath == ":") ":" else "$producerPath:") + exportPrefix(format)

    @JvmStatic
    fun exportPath(producerPath: String, format: String, publicationName: String): String =
        exportNamespace(producerPath, format) + BootstrapSpec.capitalize(publicationName)

    @JvmStatic
    private fun exportPrefix(format: String): String {
        require(!(format != "maven" && format != "ivy")) { "NEXUS_UNKNOWN_BUNDLE_FORMAT" }
        return "exportNexus" + BootstrapSpec.capitalize(format)
    }
}
