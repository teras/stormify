/* Copyright Abandoned 1996, 1999, 2001 MySQL AB
   This file is public domain and comes with NO WARRANTY of any kind */

/* Version numbers for protocol & mysqld */

#ifndef _mariadb_version_h_
#define _mariadb_version_h_

#ifdef _CUSTOMCONFIG_
#include <custom_conf.h>
#else
#define PROTOCOL_VERSION		10
#define MARIADB_CLIENT_VERSION_STR	"12.2.2"
#define MARIADB_BASE_VERSION		"mariadb-12.2"
#define MARIADB_VERSION_ID		120202
#define MARIADB_PORT	        	3306
#define MARIADB_UNIX_ADDR               "/run/mysqld/mysqld.sock"
#ifndef MYSQL_UNIX_ADDR
#define MYSQL_UNIX_ADDR MARIADB_UNIX_ADDR
#endif
#ifndef MYSQL_PORT
#define MYSQL_PORT MARIADB_PORT
#endif

#define MYSQL_CONFIG_NAME               "my"
#define MYSQL_VERSION_ID                120202
#define MYSQL_SERVER_VERSION            "12.2.2-MariaDB"

#define MARIADB_PACKAGE_VERSION "3.4.9"
#define MARIADB_PACKAGE_VERSION_ID 30409
#define MARIADB_SYSTEM_TYPE "Linux"
#define MARIADB_MACHINE_TYPE "x86_64"
#define MARIADB_PLUGINDIR "/usr/lib/mysql/plugin"

/* mysqld compile time options */
#ifndef MYSQL_CHARSET
#define MYSQL_CHARSET			""
#endif
#endif

/* Source information */
#define CC_SOURCE_REVISION "7bb4e6cdf787b32907429287a636857e3b31e6a1"

#endif /* _mariadb_version_h_ */
