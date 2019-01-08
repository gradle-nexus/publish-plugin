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

import de.marcphilipp.gradle.nexus.internal.NexusClient;
import io.codearte.gradle.nexus.NexusStagingExtension;
import lombok.Getter;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;

@SuppressWarnings({"UnstableApiUsage", "WeakerAccess"})
public class InitializeNexusStagingRepository extends DefaultTask {

    static final String NAME = "initializeNexusStagingRepository";

    private static final Logger logger = LoggerFactory.getLogger(InitializeNexusStagingRepository.class);

    private final Map<URI, URI> serverUrlToStagingRepoUrl;
    @Getter(onMethod_ = { @Input })
    private final Property<URI> serverUrl;
    @Getter(onMethod_ = { @Optional, @Input })
    private final Property<String> username;
    @Getter(onMethod_ = { @Optional, @Input })
    private final Property<String> password;
    @Getter(onMethod_ = { @Optional, @Input })
    private final Property<String> packageGroup;
    @Getter(onMethod_ = { @Optional, @Input })
    private final Property<String> stagingProfileId;
    @Getter(onMethod_ = { @Input })
    private final Property<String> repositoryName;

    @Inject
    public InitializeNexusStagingRepository(Project project, NexusPublishExtension extension, Map<URI, URI> serverUrlToStagingRepoUrl) {
        this.serverUrlToStagingRepoUrl = serverUrlToStagingRepoUrl;
        ObjectFactory objectFactory = project.getObjects();
        serverUrl = objectFactory.property(URI.class);
        serverUrl.set(extension.getServerUrl());
        username = objectFactory.property(String.class);
        username.set(extension.getUsername());
        password = objectFactory.property(String.class);
        password.set(extension.getPassword());
        packageGroup = objectFactory.property(String.class);
        packageGroup.set(extension.getPackageGroup());
        stagingProfileId = objectFactory.property(String.class);
        stagingProfileId.set(extension.getStagingProfileId());
        repositoryName = objectFactory.property(String.class);
        repositoryName.set(extension.getRepositoryName());
        onlyIf(t -> extension.getUseStaging().getOrElse(false));
    }

    @TaskAction
    public void createStagingRepoAndReplacePublishingRepoUrl() {
        URI url = serverUrlToStagingRepoUrl.computeIfAbsent(getServerUrl().get(), serverUrl -> {
            NexusClient client = new NexusClient(serverUrl, getUsername().getOrNull(), getPassword().getOrNull());
            String stagingProfileId = getStagingProfileId().getOrNull();
            if (stagingProfileId == null) {
                String packageGroup = getPackageGroup().get();
                logger.debug("No stagingProfileId set, querying for packageGroup '{}'", packageGroup);
                stagingProfileId = client.findStagingProfileId(packageGroup)
                        .orElseThrow(() -> new GradleException("Failed to find staging profile for package group: " + packageGroup));
            }
            logger.info("Creating staging repository for stagingProfileId '{}'", stagingProfileId);
            String stagingRepositoryId = client.createStagingRepository(stagingProfileId);
            getProject()
                    .getRootProject()
                    .getPlugins()
                    .withId("io.codearte.nexus-staging", nexusStagingPlugin -> {
                        NexusStagingExtension nexusStagingExtension = getProject()
                                .getRootProject()
                                .getExtensions()
                                .getByType(NexusStagingExtension.class);
                        Property<String> stagingRepositoryIdProperty;
                        try {
                            stagingRepositoryIdProperty = nexusStagingExtension.getStagingRepositoryId();
                        } catch (NoSuchMethodError nsme) {
                            logger.warn("For increased publishing reliability please update the io.codearte.nexus-staging plugin to at least version 0.20.0.\n" +
                                    "If your version is at least 0.20.0, try to update the de.marcphilipp.nexus-publish plugin to its latest version.\n" +
                                    "If this also does not make this warning go away, please report an issue for de.marcphilipp.nexus-publish.");
                            logger.debug("getStagingRepositoryId method not found on nexusStagingExtension", nsme);
                            return;
                        }
                        stagingRepositoryIdProperty.set(stagingRepositoryId);
                    });
            return client.getStagingRepositoryUri(stagingRepositoryId);
        });
        PublishingExtension publishing = getProject().getExtensions().getByType(PublishingExtension.class);
        MavenArtifactRepository repository = (MavenArtifactRepository) publishing.getRepositories().getByName(getRepositoryName().get());
        logger.info("Updating URL of publishing repository '{}' to '{}'", repository.getName(), url);
        repository.setUrl(url.toString());
    }

}
