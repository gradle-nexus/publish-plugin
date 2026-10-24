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

package io.github.gradlenexus.publishplugin.settings

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.artifacts.repositories.IvyPatternRepositoryLayout
import org.gradle.api.provider.Property
import java.net.URI
import java.util.ArrayList
import java.util.Collections
import javax.inject.Inject

/**
 * Settings-owned repository policy; never a project publication or live repository model.
 * Configure properties and Ivy layout in the settings script before the plugin's settingsEvaluated
 * snapshot. Properties and provider values finalize there; later setters, layout actions and
 * repository-container mutations are rejected. Absent credentials retain the root-only named
 * String-property conventions. This does not change the legacy Project extension.
 */
abstract class NexusRepositorySettings @Inject constructor(private val name: String) : Named {
    enum class PublicationType {
        MAVEN,
        IVY
    }

    private var explicitIvyLayout: Boolean = false
    private var layoutFrozen: Boolean = false
    var artifactPatterns: List<String> = Collections.emptyList()
        private set
    var ivyPatterns: List<String> = Collections.emptyList()
        private set
    private var m2Compatible: Boolean = false

    init {
        publicationType.convention(PublicationType.MAVEN)
        allowInsecureProtocol.convention(false)
        if (name == "sonatype") {
            nexusUrl.convention(URI.create("https://oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.convention(URI.create("https://oss.sonatype.org/content/repositories/snapshots/"))
        }
    }

    override fun getName(): String =
        name

    abstract val nexusUrl: Property<URI>

    abstract val snapshotRepositoryUrl: Property<URI>

    abstract val publicationType: Property<PublicationType>

    abstract val username: Property<String>

    abstract val password: Property<String>

    abstract val stagingProfileId: Property<String>

    /**
     * Explicit root-only session resume; never falls back to discovery or creation.
     */
    abstract val stagingRepositoryId: Property<String>

    abstract val allowInsecureProtocol: Property<Boolean>

    fun ivyPatternLayout(action: Action<in IvyPatternRepositoryLayout>) {
        if (layoutFrozen) throw NexusSettingsInternals.frozen("repositories.ivyPatternLayout")
        val collector = Layout()
        action.execute(collector)
        artifactPatterns = Collections.unmodifiableList(ArrayList(collector.artifacts))
        ivyPatterns = Collections.unmodifiableList(ArrayList(collector.ivy))
        m2Compatible = collector.m2Compatible
        explicitIvyLayout = true
    }

    fun freezeLayout() {
        layoutFrozen = true
    }

    fun isExplicitIvyLayout(): Boolean =
        explicitIvyLayout

    fun isM2Compatible(): Boolean =
        m2Compatible

    private class Layout : IvyPatternRepositoryLayout {
        @JvmField
        val artifacts: MutableList<String> = ArrayList<String>()

        @JvmField
        val ivy: MutableList<String> = ArrayList<String>()

        @JvmField
        var m2Compatible: Boolean = false

        override fun artifact(pattern: String) {
            artifacts.add(pattern)
        }

        override fun ivy(pattern: String) {
            ivy.add(pattern)
        }

        override fun getM2Compatible(): Boolean =
            m2Compatible

        override fun setM2compatible(value: Boolean) {
            m2Compatible = value
        }
    }
}
