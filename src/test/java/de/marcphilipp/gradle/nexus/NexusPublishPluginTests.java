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
import org.gradle.testkit.runner.TaskOutcome;
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
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.SKIPPED;
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
    void publishesToNexus(String extraSettings, @TempDir Path projectDir, WireMockServer server) throws IOException {
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
                "    serverUrl = uri('" + server.baseUrl() + "')",
                "    username = 'username'",
                "    password = 'password'",
                "}"));

        stubStagingProfileRequest(server, "/staging/profiles", Map.of("id", STAGING_PROFILE_ID, "name", "org.example"));
        stubCreateStagingRepoRequest(server, "/staging/profiles/" + STAGING_PROFILE_ID + "/start", STAGED_REPOSITORY_ID);
        expectArtifactUploads(server, "/staging/deployByRepositoryId/" + STAGED_REPOSITORY_ID);

        BuildResult result = runGradleBuild(projectDir, "publishToNexus");

        assertSuccess(result, ":initializeNexusStagingRepository");
        assertUploadedToStagingRepo(server, "/org/example/sample/0.0.1/sample-0.0.1.pom");
        assertUploadedToStagingRepo(server, "/org/example/sample/0.0.1/sample-0.0.1.jar");
    }

    @ParameterizedTest
    @MethodSource("publishingSettings")
    void canBeUsedWithLazilyAppliedGradlePluginDevelopmentPlugin(String extraSettings, @TempDir Path projectDir, WireMockServer server) throws IOException {
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
                "            ext.serverUrl = uri('" + server.baseUrl() + "')",
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

        stubCreateStagingRepoRequest(server, "/staging/profiles/" + STAGING_PROFILE_ID + "/start", STAGED_REPOSITORY_ID);
        expectArtifactUploads(server, "/staging/deployByRepositoryId/" + STAGED_REPOSITORY_ID);

        BuildResult result = runGradleBuild(projectDir, "publishToNexus");

        assertSuccess(result, ":gradle-plugin:initializeNexusStagingRepository");
        assertUploadedToStagingRepo(server, "/org/example/gradle-plugin/0.0.1/gradle-plugin-0.0.1.pom");
        assertUploadedToStagingRepo(server, "/org/example/foo/org.example.foo.gradle.plugin/0.0.1/org.example.foo.gradle.plugin-0.0.1.pom");
    }

    @ParameterizedTest
    @MethodSource("publishingSettings")
    void publishesSnapshots(String extraSettings, @TempDir Path projectDir, WireMockServer server) throws IOException {
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
                "version = '0.0.1-SNAPSHOT'",
                "publishing {",
                "    publications {",
                "        mavenJava(MavenPublication) {",
                "            from(components.java)",
                "        }",
                "    }",
                "}",
                "nexusPublishing {",
                "    serverUrl = uri('" + server.baseUrl() + "/shouldNotBeUsed')",
                "    snapshotRepositoryUrl = uri('" + server.baseUrl() + "/snapshots')",
                "    username = 'username'",
                "    password = 'password'",
                "}"));

        expectArtifactUploads(server, "/snapshots");

        BuildResult result = runGradleBuild(projectDir, "publishToNexus");

        assertSkipped(result, ":initializeNexusStagingRepository");
        assertUploaded(server, "/snapshots/org/example/sample/0.0.1-SNAPSHOT/sample-0.0.1-.*.pom");
        assertUploaded(server, "/snapshots/org/example/sample/0.0.1-SNAPSHOT/sample-0.0.1-.*.jar");
    }

    @ParameterizedTest
    @MethodSource("publishingSettings")
    void createsSingleStagingRepositoryPerServerUrl(String extraSettings, @TempDir Path projectDir, WireMockServer server) throws IOException {
        Files.write(projectDir.resolve("settings.gradle"), List.of(
                "rootProject.name = 'sample'",
                extraSettings,
                "include 'a1', 'a2', 'b'"
        ));

        Files.write(projectDir.resolve("build.gradle"), List.of(
                "plugins {",
                "    id('de.marcphilipp.nexus-publish') apply false",
                "}",
                "subprojects {",
                "    apply plugin: 'java-library'",
                "    apply plugin: 'de.marcphilipp.nexus-publish'",
                "    group = 'org.example'",
                "    version = '0.0.1'",
                "    publishing {",
                "        publications {",
                "            mavenJava(MavenPublication) {",
                "                from(components.java)",
                "            }",
                "        }",
                "    }",
                "    nexusPublishing {",
                "        serverUrl = uri('" + server.baseUrl() + "/a/')",
                "        stagingProfileId = 'profile-a'",
                "        username = 'username'",
                "        password = 'password'",
                "    }",
                "}",
                "project(':b:') {",
                "    nexusPublishing {",
                "        serverUrl = uri('" + server.baseUrl() + "/b/')",
                "        stagingProfileId = 'profile-b'",
                "    }",
                "}"));

        stubCreateStagingRepoRequest(server, "/a/staging/profiles/profile-a/start", "orgexample-a");
        expectArtifactUploads(server, "/a/staging/deployByRepositoryId/orgexample-a");
        stubCreateStagingRepoRequest(server, "/b/staging/profiles/profile-b/start", "orgexample-b");
        expectArtifactUploads(server, "/b/staging/deployByRepositoryId/orgexample-b");

        BuildResult result = runGradleBuild(projectDir, "publishToNexus", "--parallel");

        server.verify(1, postRequestedFor(urlEqualTo("/a/staging/profiles/profile-a/start")));
        server.verify(1, postRequestedFor(urlEqualTo("/b/staging/profiles/profile-b/start")));
        assertSuccess(result, ":a1:initializeNexusStagingRepository");
        assertSuccess(result, ":a2:initializeNexusStagingRepository");
        assertSuccess(result, ":b:initializeNexusStagingRepository");
        assertUploaded(server, "/a/staging/deployByRepositoryId/orgexample-a/org/example/a1/0.0.1/a1-0.0.1.pom");
        assertUploaded(server, "/a/staging/deployByRepositoryId/orgexample-a/org/example/a2/0.0.1/a2-0.0.1.pom");
        assertUploaded(server, "/b/staging/deployByRepositoryId/orgexample-b/org/example/b/0.0.1/b-0.0.1.jar");
    }

    private BuildResult runGradleBuild(Path projectDir, String... arguments) {
        return getGradleRunner()
                .withProjectDir(projectDir.toFile())
                .withArguments(arguments)
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

    private static void assertSuccess(BuildResult result, String taskPath) {
        assertOutcome(result, taskPath, SUCCESS);
    }

    private static void assertSkipped(BuildResult result, String taskPath) {
        assertOutcome(result, taskPath, SKIPPED);
    }

    private static void assertOutcome(BuildResult result, String taskPath, TaskOutcome outcome) {
        assertThat(result.task(taskPath)).isNotNull()
                .extracting(BuildTask::getOutcome).isEqualTo(outcome);
    }

    private static void assertUploadedToStagingRepo(WireMockServer server, String path) {
        assertUploaded(server, "/staging/deployByRepositoryId/" + STAGED_REPOSITORY_ID + path);
    }

    private static void assertUploaded(WireMockServer server, String testUrl) {
        server.verify(putRequestedFor(urlMatching(testUrl)));
    }

    @SafeVarargs
    private void stubStagingProfileRequest(WireMockServer server, String url, Map<String, String>... stagingProfiles) {
        server.stubFor(get(urlEqualTo(url))
                .willReturn(aResponse().withBody(gson.toJson(Map.of("data", List.of(stagingProfiles))))));
    }

    private void stubCreateStagingRepoRequest(WireMockServer server, String url, String stagedRepositoryId) {
        server.stubFor(post(urlEqualTo(url))
                .willReturn(aResponse().withBody(gson.toJson(Map.of("data", Map.of("stagedRepositoryId", stagedRepositoryId))))));
    }

    private void expectArtifactUploads(WireMockServer server, String prefix) {
        server.stubFor(put(urlMatching(prefix + "/.+"))
                .willReturn(aResponse().withStatus(201)));
        server.stubFor(get(urlMatching(prefix + "/.+/maven-metadata.xml"))
                .willReturn(aResponse().withStatus(404)));
    }
}