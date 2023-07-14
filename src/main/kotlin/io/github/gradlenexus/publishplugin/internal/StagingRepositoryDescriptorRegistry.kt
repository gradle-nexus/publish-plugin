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

package io.github.gradlenexus.publishplugin.internal

import java.util.concurrent.ConcurrentHashMap

open class StagingRepositoryDescriptorRegistry {

    private val mapping = ConcurrentHashMap<String, StagingRepositoryDescriptor>()

    open operator fun set(name: String, descriptor: StagingRepositoryDescriptor) {
        mapping[name] = descriptor
    }

    operator fun get(name: String) = mapping[name] ?: error("No staging repository with name $name created")

    fun tryGet(name: String) = mapping[name]

    override fun toString() = mapping.toString()
}
