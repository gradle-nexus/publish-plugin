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

import lombok.Data;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import java.net.URI;

@Data
@SuppressWarnings("UnstableApiUsage")
public class NexusPublishExtension {

    static final String NAME = "nexusPublishing";

    private final Property<Boolean> useStaging;
    private final Property<URI> serverUrl;
    private final Property<URI> snapshotRepositoryUrl;
    private final Property<String> username;
    private final Property<String> password;
    private final Property<String> repositoryName;
    private final Property<String> packageGroup;
    private final Property<String> stagingProfileId;

    public NexusPublishExtension(Project project) {
        ObjectFactory objectFactory = project.getObjects();
        useStaging = objectFactory.property(Boolean.class);
        useStaging.set(project.provider(() -> !project.getVersion().toString().endsWith("-SNAPSHOT")));
        serverUrl = objectFactory.property(URI.class);
        serverUrl.set(URI.create("https://oss.sonatype.org/service/local/"));
        snapshotRepositoryUrl = objectFactory.property(URI.class);
        snapshotRepositoryUrl.set(URI.create("https://oss.sonatype.org/content/repositories/snapshots/"));
        username = objectFactory.property(String.class);
        username.set(project.provider(() -> (String) project.findProperty("nexusUsername")));
        password = objectFactory.property(String.class);
        password.set(project.provider(() -> (String) project.findProperty("nexusPassword")));
        repositoryName = objectFactory.property(String.class);
        repositoryName.set("nexus");
        packageGroup = objectFactory.property(String.class);
        packageGroup.set(project.provider(() -> project.getGroup().toString()));
        stagingProfileId = objectFactory.property(String.class);
    }
}
