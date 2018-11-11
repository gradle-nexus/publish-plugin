/*
 * Copyright 2018 the original author or authors.
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

package de.marcphilipp.gradle.nexus;

import io.codearte.gradle.nexus.NexusStagingExtension;
import io.codearte.gradle.nexus.NexusStagingPlugin;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class StagingPluginIntegrationTest {

    private Project project;

    @BeforeEach
    public void setUp() {
        project = ProjectBuilder.builder().build();
        project.getPlugins().apply(NexusPublishPlugin.class);
        project.getPlugins().apply(NexusStagingPlugin.class);
    }

    @Test
    public void defaultWireing() {
        NexusPublishExtension ourExtension = project.getExtensions().getByType(NexusPublishExtension.class);
        NexusStagingExtension theirExtension = project.getExtensions().getByType(NexusStagingExtension.class);

        theirExtension.setPackageGroup("foo");
        assertThat(ourExtension.getPackageGroup().getOrNull()).isEqualTo("foo");

        theirExtension.setStagingProfileId("12345678");
        assertThat(ourExtension.getStagingProfileId().getOrNull()).isEqualTo("12345678");

        theirExtension.setUsername("bar");
        assertThat(ourExtension.getUsername().getOrNull()).isEqualTo("bar");

        theirExtension.setPassword("secret");
        assertThat(ourExtension.getPassword().getOrNull()).isEqualTo("secret");
    }

    @Test
    public void explicitValuesWin() {
        NexusPublishExtension ourExtension = project.getExtensions().getByType(NexusPublishExtension.class);
        NexusStagingExtension theirExtension = project.getExtensions().getByType(NexusStagingExtension.class);

        ourExtension.getUsername().set("foo");
        theirExtension.setUsername("bar");
        assertThat(ourExtension.getUsername().getOrNull()).isEqualTo("foo");

        theirExtension.setPassword("secret1");
        ourExtension.getPassword().set("secret2");
        assertThat(ourExtension.getPassword().getOrNull()).isEqualTo("secret2");
    }
}
