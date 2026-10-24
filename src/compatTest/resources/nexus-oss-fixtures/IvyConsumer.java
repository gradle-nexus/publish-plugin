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

import org.apache.ivy.Ivy;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.plugins.resolver.URLResolver;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

/** Independent public Ivy API consumer in its own JVM, with no user settings/auth registration. */
public class IvyConsumer {
    public static void main(String[] args) throws Exception {
        File home = new File(args[0]);
        IvySettings settings = new IvySettings();
        settings.setBaseDir(home);
        settings.setDefaultCache(new File(home, "cache"));
        settings.setVariable("ivy.default.ivy.user.dir", home.getAbsolutePath());
        URLResolver resolver = new URLResolver();
        resolver.setName("owned-loopback"); resolver.setM2compatible(true);
        resolver.addIvyPattern(args[1] + args[7]);
        resolver.addArtifactPattern(args[1] + args[6]);
        settings.addResolver(resolver); settings.setDefaultResolver(resolver.getName());
        ResolveReport report = Ivy.newInstance(settings).resolve(ModuleRevisionId.newInstance(args[2], args[3], args[4]), new ResolveOptions().setConfs(new String[]{"default"}).setTransitive(false), false);
        if (report.hasError()) throw new IllegalStateException("Real OSS public Ivy resolution failed");
        boolean found = false;
        for (var artifact : report.getAllArtifactsReports()) {
            File file = artifact.getLocalFile();
            if (file == null || !file.isFile()) continue;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
            String hash = java.util.HexFormat.of().formatHex(digest);
            if (hash.equals(args[5])) found = true;
        }
        if (!found) throw new IllegalStateException("Public Ivy resolved no matching Java artifact");
        System.out.println("OSS-PUBLIC-IVY-CONSUMED " + Ivy.class.getProtectionDomain().getCodeSource().getLocation());
    }
}
