rootProject.name = "stormify"

include(":stormify")
include(":logger")
include(":annproc")
include(":kdbc")
include(":kdbc-sqlite")
include(":kdbc-mariadb")
include(":kdbc-postgres")
include(":kdbc-oracle")
include(":kdbc-freetds")

project(":kdbc").projectDir = file("kdbc/kdbc")
project(":kdbc-sqlite").projectDir = file("kdbc/kdbc-sqlite")
project(":kdbc-mariadb").projectDir = file("kdbc/kdbc-mariadb")
project(":kdbc-postgres").projectDir = file("kdbc/kdbc-postgres")
project(":kdbc-oracle").projectDir = file("kdbc/kdbc-oracle")
project(":kdbc-freetds").projectDir = file("kdbc/kdbc-freetds")