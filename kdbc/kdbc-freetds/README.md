# kdbc-freetds - MS SQL Server Driver for Kotlin/Native

FreeTDS-based native driver for MS SQL Server on Linux.

## Status

⚠️ **Work in Progress** - This module is under active development.

## Prerequisites

Install FreeTDS on your system:
```bash
# Arch/Manjaro
sudo pacman -S freetds

# Debian/Ubuntu
sudo apt-get install freetds-dev

# Fedora
sudo dnf install freetds-devel
```

## Usage

```kotlin
import onl.ycode.kdbc.freetds.FreeTDSDataSource

val dataSource = FreeTDSDataSource(
    host = "localhost",
    port = 1433,
    database = "mydb",
    user = "sa",
    password = "YourPassword"
)

val conn = dataSource.getConnection()
// Use connection...
conn.close()
```

## Implementation Notes

- FreeTDS db-lib doesn't have native prepared statements like libpq or MySQL
- Parameters are handled by building parameterized SQL with proper escaping
- Error handling uses FreeTDS error/message callbacks
- Transaction support uses T-SQL BEGIN/COMMIT/ROLLBACK commands

## Known Limitations

- CallableStatement (stored procedures) support is basic
- Binary data handling is simplified
- Some complex data types may not be fully supported yet
