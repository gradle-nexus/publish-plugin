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

package io.github.gradlenexus.publishplugin.internal.modern

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.initialization.ProjectDescriptor
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import java.util.ArrayList
import java.util.Collections

/**
 * Configuration-only descriptor path check; no coordinator values or root execution state.
 */
internal class VerifySettingsFreeze(private val settings: Settings, private val bootstrap: BootstrapSpec) : Action<Gradle> {

    override fun execute(ignored: Gradle) {
        val paths: MutableList<String> = ArrayList<String>()
        collect(settings.rootProject, paths)
        Collections.sort(paths)
        if (bootstrap.getProjectPaths() != paths) throw GradleException("NEXUS_SETTINGS_CHANGED_AFTER_FREEZE: project paths. Configure project includes directly in the settings script before its settingsEvaluated snapshot.")
    }

    companion object {
        @JvmStatic
        private fun collect(descriptor: ProjectDescriptor, paths: MutableList<String>) {
            paths.add(descriptor.path)
            for (child in descriptor.children) collect(child, paths)
        }
    }
}
