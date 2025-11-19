plugins {
    id("com.gradle.develocity") version "4.0.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

develocity {
    buildScan {
        termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
        termsOfUseAgree = "yes"
        uploadInBackground = false
        publishing.onlyIf { System.getenv("CI") != null }
    }
}

rootProject.name = "gradle-nexus-publish-plugin"
