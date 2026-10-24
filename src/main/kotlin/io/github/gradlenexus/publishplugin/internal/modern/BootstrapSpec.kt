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

import java.io.Serializable
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet

/**
 * The only settings data carried by the universal isolated callback.
 */
class BootstrapSpec @JvmOverloads constructor(
    paths: List<String>,
    exclusions: Set<String>,
    private val suppressIvyModuleMetadata: Boolean,
    repositories: List<RepositoryRoute> = Collections.emptyList()
) : Serializable {
    fun getFormats(): List<String> =
        Collections.unmodifiableList(listOf("maven", "ivy"))

    fun collectionTaskPath(producerPath: String, format: String): String {
        val index: Int = projectPaths.indexOf(producerPath)
        require(!(index < 0 || !getFormats().contains(format))) { "Unknown producer or bundle format" }
        return ":collectNexus" + capitalize(format) + "Bundle" + index
    }

    class RepositoryRoute(private val name: String, val format: String, private val scope: String) : Serializable {
        fun getName(): String =
            name

        fun getScope(): String =
            scope
        val resetPath: String
            get() {
                return ":" + scope + "_reset"
            }
        fun unitPath(index: Int): String =
            ":" + scope + "_unit" + index
        fun getLocalAlias(): String =
            "publishTo" + capitalize(name)
        fun getAggregateAlias(): String =
            "publishAllProjectsTo" + capitalize(name)
    }
    val repositories: List<RepositoryRoute> = Collections.unmodifiableList(ArrayList(repositories))

    fun getWorkflowResetPaths(): List<String> {
        val result: MutableList<String> = ArrayList<String>()
        for (route in repositories) result.add(route.resetPath)
        return Collections.unmodifiableList(result)
    }
    private val projectPaths: List<String> = Collections.unmodifiableList<String>(ArrayList<String>(paths))
    private val exclusions: Set<String> = Collections.unmodifiableSet(LinkedHashSet(exclusions))

    fun getProjectPaths(): List<String> =
        projectPaths

    fun getExclusions(): Set<String> =
        exclusions

    fun isSuppressIvyModuleMetadata(): Boolean =
        suppressIvyModuleMetadata

    companion object {
        const val BUNDLE_SCHEMA: String = "bundle-1"

        @JvmStatic
        fun capitalize(value: String): String =
            Character.toUpperCase(value[0]).toString() + value.substring(1)
    }
}
