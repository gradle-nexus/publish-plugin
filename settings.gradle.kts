plugins {
    id("com.gradle.enterprise") version "3.14"
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.6.0"
}

val isCiBuild = System.getenv("CI") != null

gradleEnterprise {
    buildScan {
        termsOfServiceUrl = "https://gradle.com/terms-of-service"
        termsOfServiceAgree = "yes"
        publishAlwaysIf(isCiBuild)
    }
}

// this needs to stay this way since it's used as the plugin's artifact id
// for generating the plugin marker
rootProject.name = "publish-plugin"
