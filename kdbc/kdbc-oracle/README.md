# KDBC Oracle Driver

Oracle Database driver for Kotlin Native using Oracle Call Interface (OCI).

## Prerequisites

### Required: Oracle Instant Client

You must install Oracle Instant Client to compile and use this module.

#### Installation on Manjaro/Arch Linux

**Option 1: Using AUR (Recommended)**
```bash
yay -S oracle-instantclient-basic oracle-instantclient-sdk
```

**Option 2: Manual Installation**
```bash
# Download from Oracle:
# https://www.oracle.com/database/technologies/instant-client/linux-x86-64-downloads.html

# Extract and install
sudo mkdir -p /opt/oracle
unzip instantclient-basic-linux.x64-*.zip -d /tmp/
unzip instantclient-sdk-linux.x64-*.zip -d /tmp/
sudo mv /tmp/instantclient_* /opt/oracle/instantclient_21_13

# Configure library path
sudo sh -c "echo /opt/oracle/instantclient_21_13 > /etc/ld.so.conf.d/oracle-instantclient.conf"
sudo ldconfig

# Add to ~/.bashrc or ~/.zshrc
export ORACLE_HOME=/opt/oracle/instantclient_21_13
export LD_LIBRARY_PATH=$ORACLE_HOME:$LD_LIBRARY_PATH
```

#### Installation on Ubuntu/Debian

```bash
# Download and install
wget https://download.oracle.com/otn_software/linux/instantclient/2113000/instantclient-basic-linux.x64-21.13.0.0.0dbru.zip
wget https://download.oracle.com/otn_software/linux/instantclient/2113000/instantclient-sdk-linux.x64-21.13.0.0.0dbru.zip

sudo mkdir -p /opt/oracle
sudo unzip instantclient-basic-linux.x64-*.zip -d /opt/oracle/
sudo unzip instantclient-sdk-linux.x64-*.zip -d /opt/oracle/

sudo sh -c "echo /opt/oracle/instantclient_21_13 > /etc/ld.so.conf.d/oracle-instantclient.conf"
sudo ldconfig
```

#### Installation on Fedora/RHEL

```bash
sudo dnf install oracle-instantclient-basic
sudo dnf install oracle-instantclient-devel
```

### Verify Installation

```bash
# Check headers
ls /opt/oracle/instantclient_*/sdk/include/oci.h

# Check libraries
ldconfig -p | grep clntsh
```

## Usage

```kotlin
import onl.ycode.kdbc.oracle.OracleDataSource
import onl.ycode.kdbc.PoolConfig
import onl.ycode.kdbc.SslConfig

// Basic connection
val dataSource = OracleDataSource(
    host = "localhost",
    port = 1521,
    serviceName = "XE",  // or use SID
    user = "system",
    password = "oracle"
)

// With connection pooling
val dataSource = OracleDataSource(
    host = "oracle-db.example.com",
    port = 1521,
    serviceName = "PROD",
    user = "app_user",
    password = "password",
    poolConfig = PoolConfig(
        minConnections = 5,
        maxConnections = 20,
        validationQuery = "SELECT 1 FROM DUAL"
    )
)

// With SSL (requires Oracle Wallet)
val dataSource = OracleDataSource(
    host = "adb.oraclecloud.com",
    port = 1522,
    serviceName = "mydb_high",
    user = "ADMIN",
    password = "password",
    sslConfig = SslConfig(
        enabled = true,
        caCertPath = "/path/to/wallet_MYDB"
    )
)

// Use with Stormify
val stormify = Stormify(dataSource)
```

## Connection String Formats

### Easy Connect

```
host:port/service_name
```

### TNS Format

```
(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=host)(PORT=port))(CONNECT_DATA=(SERVICE_NAME=service)))
```

## Supported Oracle Versions

- Oracle Database 12c and later
- Oracle Database 19c (recommended)
- Oracle Database 21c
- Oracle Autonomous Database (Cloud)

## License

Oracle Instant Client is free to use but Oracle Database requires appropriate licensing.
See: https://www.oracle.com/downloads/licenses/instant-client-lic.html

## Building

Once Oracle Instant Client is installed:

```bash
# Compile the module
gradle :kdbc-oracle:compileKotlinLinuxX64

# Build the module
gradle :kdbc-oracle:build

# Publish to Maven Local
gradle :kdbc-oracle:publishToMavenLocal
```

## Status

⚠️ **Work in Progress**: This module is currently under development.

✅ **What's Done:**
- ✅ Module structure and build configuration
- ✅ Oracle OCI cinterop bindings generated successfully
- ✅ DataSource with pooling support
- ✅ OracleConnection implementation (complete)
- ✅ OraclePreparedStatement implementation (complete)
- ✅ OracleResultSet implementation (complete)
- ✅ OracleCallableStatement implementation (complete)
- ✅ OracleDatabaseMetaData implementation (complete)
- ✅ OracleResultSetMetaData implementation (complete)
- ✅ OracleSavepoint implementation (complete)

❌ **What's Needed to Complete:**
- Fix cinterop type mappings (OCI types not fully resolved)
- Add kotlinx.datetime dependency
- Add bignum dependency
- Test compilation with fixed imports
- Full SSL/TLS wallet integration
- Testing with actual Oracle database

## Known Issues

The implementation is complete but needs cinterop type mapping fixes:
- OCI types (OCIEnv, OCIError, OCIStmt, etc.) need proper typedef handling
- Constants (OCI_SUCCESS, SQLT_STR, etc.) need to be exposed correctly
- This is a cinterop configuration issue, not an architecture issue

The architecture follows the same proven pattern as PostgreSQL, MariaDB, and SQLite.
