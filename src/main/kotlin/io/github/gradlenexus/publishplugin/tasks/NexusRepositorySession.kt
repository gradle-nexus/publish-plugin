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

package io.github.gradlenexus.publishplugin.tasks

import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input

/**
 * Repository acquisition/adoption work, including automatic routing and public initialize/find.
 * Configure shared prerequisite Tasks on every relevant session task to guard acquisition;
 * an upload prerequisite can run after acquisition. Shared prerequisite Tasks deduplicate,
 * but actions run separately for public acquisition and automatic adoption when both are selected.
 * Actions also run for direct nonstaging handle preparation, unlike legacy initialize actions.
 * This type does not enroll standalone explicit-ID close/release operations.
 */
interface NexusRepositorySession : Task {
    @get:Input val repositoryAlias: Provider<String>
}
