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

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Local bundle validation requires current native export evidence")
abstract class CollectBundles : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundles: ConfigurableFileCollection

    @get:Input abstract val producerPath: Property<String>

    @get:Input abstract val format: Property<String>

    @get:Input abstract val excluded: Property<Boolean>

    init {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Bundle validation must execute") { true }
    }

    @TaskAction
    @Throws(Exception::class)
    fun collect() {
        if (excluded.get()) {
            logger.lifecycle("NEXUS-EXCLUDED {} {}: no local bundle collection", producerPath.get(), format.get())
            return
        }
        require(bundles.files.size == 1) { "NEXUS_INVALID_BUNDLE: expected one producer bundle" }
        for (directory in bundles) {
            val bundle: Map<String, Any> = BundleCodec.readModel(directory.toPath())
            require(!(!producerPath.get().equals(bundle["producerPath"]) || !format.get().equals(bundle["format"]))) { "NEXUS_INVALID_BUNDLE: producer or format mismatch" }
            logger.lifecycle("NEXUS-COLLECTED " + bundle["producerPath"] + " " + bundle["format"] + " empty=" + bundle["empty"])
        }
    }
}
