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

import org.gradle.BuildAdapter;
import org.gradle.BuildResult;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.publish.plugins.PublishingPlugin;
import org.gradle.api.tasks.TaskProvider;

import javax.annotation.Nonnull;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("UnstableApiUsage")
class NexusPublishPlugin implements Plugin<Project> {

    private static final String PUBLISH_TO_NEXUS_LIFECYCLE_TASK_NAME = "publishToNexus";
    private static final Map<URI, URI> serverUrlToStagingRepoUrl = new ConcurrentHashMap<>();

    @Override
    public void apply(@Nonnull Project project) {
        project.getPluginManager().apply(MavenPublishPlugin.class);

        project.getGradle().addBuildListener(new BuildAdapter() {
            @Override
            public void buildFinished(BuildResult result) {
                serverUrlToStagingRepoUrl.clear();
            }
        });

        NexusPublishExtension extension = project.getExtensions().create(NexusPublishExtension.NAME, NexusPublishExtension.class, project);
        TaskProvider<Task> publishToNexusTask = project.getTasks().register(PUBLISH_TO_NEXUS_LIFECYCLE_TASK_NAME, task -> {
            task.setDescription("Publishes all Maven publications produced by this project to Nexus.");
            task.setGroup(PublishingPlugin.PUBLISH_TASK_GROUP);
        });
        TaskProvider<InitializeNexusStagingRepository> initializeTask = project.getTasks()
                .register(InitializeNexusStagingRepository.NAME, InitializeNexusStagingRepository.class, project, extension, serverUrlToStagingRepoUrl);

        project.afterEvaluate(evaluatedProject -> {
            MavenArtifactRepository nexusRepository = addMavenRepository(evaluatedProject, extension);
            configureTaskDependencies(evaluatedProject, publishToNexusTask, initializeTask, nexusRepository);
        });
    }

    private MavenArtifactRepository addMavenRepository(Project project, NexusPublishExtension extension) {
        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        return publishing.getRepositories().maven(repository -> {
            repository.setName(extension.getRepositoryName().get());
            repository.setUrl(getRepoUrl(extension));
            repository.credentials(c -> {
                c.setUsername(extension.getUsername().getOrNull());
                c.setPassword(extension.getPassword().getOrNull());
            });
        });
    }

    private void configureTaskDependencies(@Nonnull Project project, TaskProvider<Task> publishToNexusTask, TaskProvider<InitializeNexusStagingRepository> initializeTask, MavenArtifactRepository nexusRepository) {
        project.getTasks()
                .withType(PublishToMavenRepository.class)
                .matching(publishTask -> publishTask.getRepository().equals(nexusRepository))
                .configureEach(publishTask -> {
                    publishTask.dependsOn(initializeTask);
                    publishTask.doFirst(t -> System.out.println("Uploading to " + publishTask.getRepository().getUrl()));
                    publishToNexusTask.configure(task -> task.dependsOn(publishTask));
                });
    }

    private URI getRepoUrl(NexusPublishExtension nexusPublishExtension) {
        return shouldUseStaging(nexusPublishExtension) ? nexusPublishExtension.getServerUrl().get() : nexusPublishExtension.getSnapshotRepositoryUrl().get();
    }

    private Boolean shouldUseStaging(NexusPublishExtension nexusPublishExtension) {
        return nexusPublishExtension.getUseStaging().get();
    }
}
