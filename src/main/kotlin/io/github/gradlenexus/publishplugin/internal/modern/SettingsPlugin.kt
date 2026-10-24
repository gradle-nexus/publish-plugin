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

import io.github.gradlenexus.publishplugin.internal.modern.BootstrapSpec.RepositoryRoute
import io.github.gradlenexus.publishplugin.settings.NexusPublishSettings
import io.github.gradlenexus.publishplugin.settings.NexusRepositorySettings
import io.github.gradlenexus.publishplugin.settings.NexusSettingsInternals
import org.gradle.api.Plugin
import org.gradle.api.initialization.ProjectDescriptor
import org.gradle.api.initialization.Settings
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.ArrayList
import java.util.Locale

class SettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        settings.gradle.sharedServices.registerIfAbsent("io.github.gradle-nexus.publish-plugin.settings-mode", SettingsModeMarker::class.java) { }
        val extension: NexusPublishSettings = settings.extensions.create("nexusPublishing", NexusPublishSettings::class.java)
        settings.gradle.settingsEvaluated { evaluated ->
            NexusSettingsInternals.freeze(extension)
            val paths: MutableList<String> = ArrayList<String>()
            collect(evaluated.rootProject, paths)
            paths.sort()
            var suppressIvy = false
            for (repository in extension.repositories) {
                if (repository.publicationType.get() === NexusRepositorySettings.PublicationType.IVY && repository.isExplicitIvyLayout()) {
                    suppressIvy =
                        true
                }
            }
            val routes: MutableList<RepositoryRoute> = ArrayList<RepositoryRoute>()
            for (repository in extension.repositories) {
                val identity: String = evaluated.settingsDir.toPath().toAbsolutePath().normalize()
                    .toString() + "\u0000" + repository.getName()
                val scope: String
                try {
                    val hash: ByteArray =
                        MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
                    val text: StringBuilder = StringBuilder("nexus_")
                    for (value in hash) text.append(String.format("%02x", value.toInt() and 255))
                    scope = text.toString()
                } catch (impossible: NoSuchAlgorithmException) {
                    throw IllegalStateException(impossible)
                }
                routes.add(
                    RepositoryRoute(
                        repository.getName(),
                        repository.publicationType.get().name.toLowerCase(Locale.ROOT),
                        scope
                    )
                )
            }
            val bootstrap = BootstrapSpec(paths, extension.excludedProjects.get(), suppressIvy, routes)
            val coordinator = CoordinatorSpec(extension)
            evaluated.gradle.lifecycle.beforeProject(ApplyProducer(bootstrap))
            evaluated.gradle.rootProject(DeliverCoordinator(coordinator))
            evaluated.gradle.projectsLoaded(VerifySettingsFreeze(evaluated, bootstrap))
        }
    }

    companion object {
        @JvmStatic
        private fun collect(project: ProjectDescriptor, paths: MutableList<String>) {
            paths.add(project.path)
            for (child in project.children) collect(child, paths)
        }
    }
}
