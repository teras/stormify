plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "stormify"

include("annproc")
include("biglist")
include("browse")
include("db")
include("kotlin")
include("logger")
include("tokenizer")
