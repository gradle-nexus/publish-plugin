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

import com.github.tomakehurst.wiremock.WireMockServer;
import com.google.gson.Gson;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junitpioneer.jupiter.TempDirectory;
import org.junitpioneer.jupiter.TempDirectory.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

@ExtendWith(TempDirectory.class)
@ExtendWith(WireMockExtension.class)
class NexusPublishPluginTests {

    private static final String STAGING_PROFILE_ID = "someProfileId";
    private static final String STAGED_REPOSITORY_ID = "orgexample-42";

    private Gson gson = new Gson();

    private static List<String> publishingSettings() {
        return List.of("// no extra settings", "enableFeaturePreview('STABLE_PUBLISHING')");
    }

    @ParameterizedTest
    @MethodSource("publishingSettings")
    void publishesToNexus(String extraSettings, @TempDir Path projectDir, WireMockServer wireMockServer) throws IOException {
        Files.write(projectDir.resolve("settings.gradle"), List.of(
                "rootProject.name = 'sample'",
                extraSettings
        ));

        Files.write(projectDir.resolve("build.gradle"), List.of(
                "plugins {",
                "    id('java-library')",
                "    id('de.marcphilipp.nexus-publish')",
                "}",
                "group = 'org.example'",
                "version = '0.0.1'",
                "publishing {",
                "    publications {",
                "        mavenJava(MavenPublication) {",
                "            from(components.java)",
                "        }",
                "    }",
                "}",
                "nexusPublishing {",
                "    serverUrl = uri('" + wireMockServer.baseUrl() + "')",
                "    username = 'username'",
                "    password = 'password'",
                "}"));

        stubStagingProfileRequest(wireMockServer, Map.of("id", STAGING_PROFILE_ID, "name", "org.example"));
        stubCreateStagingRepoRequest(wireMockServer);
        expectArtifactUploads(wireMockServer);

        BuildResult result = runGradleBuild(projectDir, "publishToNexus");

        assertThat(result.task(":initializeNexusStagingRepository")).isNotNull()
                .extracting(BuildTask::getOutcome).isEqualTo(SUCCESS);
        assertUploadedToStagingRepo(wireMockServer, "/org/example/sample/0.0.1/sample-0.0.1.pom");
        assertUploadedToStagingRepo(wireMockServer, "/org/example/sample/0.0.1/sample-0.0.1.jar");
    }

    @ParameterizedTest
    @MethodSource("publishingSettings")
    void canBeUsedWithLazilyAppliedGradlePluginDevelopmentPlugin(String extraSettings, @TempDir Path projectDir, WireMockServer wireMockServer) throws IOException {
        Files.write(projectDir.resolve("settings.gradle"), List.of(
                "rootProject.name = 'sample'",
                extraSettings,
                "include 'gradle-plugin'"
        ));

        Files.write(projectDir.resolve("build.gradle"), List.of(
                "buildscript {",
                "    dependencies {",
                "        classpath files(" + getPluginClasspathAsString() + ")",
                "    }",
                "}",
                "allprojects {",
                "    plugins.withId('maven-publish') {",
                "        project.apply plugin: 'de.marcphilipp.nexus-publish'",
                "        project.extensions.configure('nexusPublishing') { ext ->",
                "            ext.serverUrl = uri('" + wireMockServer.baseUrl() + "')",
                "            ext.stagingProfileId = '" + STAGING_PROFILE_ID + "'",
                "            ext.username = 'username'",
                "            ext.password = 'password'",
                "        }",
                "    }",
                "}"));

        Path pluginDir = Files.createDirectories(projectDir.resolve("gradle-plugin"));
        Files.write(pluginDir.resolve("build.gradle"), List.of(
                "plugins {",
                "    id('maven-publish')",
                "    id('java-gradle-plugin')",
                "}",
                "gradlePlugin {",
                "    plugins {",
                "        foo {",
                "            id = 'org.example.foo'",
                "            implementationClass = 'org.example.FooPlugin'",
                "        }",
                "    }",
                "}",
                "group = 'org.example'",
                "version = '0.0.1'"));
        Path srcDir = Files.createDirectories(pluginDir.resolve("src/main/java/org/example/"));
        Files.write(srcDir.resolve("FooPlugin.java"), List.of(
                "import org.gradle.api.*;",
                "public class FooPlugin implements Plugin<Project> {",
                "    public void apply(Project p) {}",
                "}"));

        stubCreateStagingRepoRequest(wireMockServer);
        expectArtifactUploads(wireMockServer);

        BuildResult result = runGradleBuild(projectDir, "publishToNexus");

        assertThat(result.task(":gradle-plugin:initializeNexusStagingRepository")).isNotNull()
                .extracting(BuildTask::getOutcome).isEqualTo(SUCCESS);
        assertUploadedToStagingRepo(wireMockServer, "/org/example/gradle-plugin/0.0.1/gradle-plugin-0.0.1.pom");
        assertUploadedToStagingRepo(wireMockServer, "/org/example/foo/org.example.foo.gradle.plugin/0.0.1/org.example.foo.gradle.plugin-0.0.1.pom");
    }

    private BuildResult runGradleBuild(Path projectDir, String task) {
        return getGradleRunner()
                .withProjectDir(projectDir.toFile())
                .withArguments(task)
                .forwardOutput()
                .build();
    }

    private String getPluginClasspathAsString() {
        return getGradleRunner()
                .getPluginClasspath().stream()
                .map(File::getAbsolutePath)
                .map(path -> path.replace('\\', '/'))
                .collect(joining("', '", "'", "'"));
    }

    private GradleRunner getGradleRunner() {
        return GradleRunner.create().withPluginClasspath();
    }

    private static void assertUploadedToStagingRepo(WireMockServer wireMockServer, String path) {
        wireMockServer.verify(putRequestedFor(urlEqualTo("/staging/deployByRepositoryId/" + STAGED_REPOSITORY_ID + path)));
    }

    @SafeVarargs
    private void stubStagingProfileRequest(WireMockServer wireMockServer, Map<String, String>... stagingProfiles) {
        wireMockServer.stubFor(get(urlEqualTo("/staging/profiles"))
                .willReturn(aResponse().withBody(gson.toJson(Map.of("data", List.of(stagingProfiles))))));
    }

    private void stubCreateStagingRepoRequest(WireMockServer wireMockServer) {
        wireMockServer.stubFor(post(urlEqualTo("/staging/profiles/" + STAGING_PROFILE_ID + "/start"))
                .willReturn(aResponse().withBody(gson.toJson(Map.of("data", Map.of("stagedRepositoryId", STAGED_REPOSITORY_ID))))));
    }

    private void expectArtifactUploads(WireMockServer wireMockServer) {
        wireMockServer.stubFor(put(urlMatching("/staging/deployByRepositoryId/" + STAGED_REPOSITORY_ID + "/.+"))
                .willReturn(aResponse().withStatus(201)));
        wireMockServer.stubFor(get(urlMatching("/staging/deployByRepositoryId/" + STAGED_REPOSITORY_ID + "/.+/maven-metadata.xml"))
                .willReturn(aResponse().withStatus(404)));
    }
}