CC       = gcc

# ---------------------------------------------------------------------------
# Portable include path discovery
#
# Different distros place database client headers in different locations:
#   - libpq-fe.h:    /usr/include (Arch, Fedora) vs /usr/include/postgresql (Debian, Ubuntu, RHEL)
#   - mysql.h:       /usr/include/mysql (Debian/Ubuntu, Arch) vs /usr/include/mariadb (some)
#   - sqlite3.h:     always /usr/include
#   - dpi.h:         /usr/include (when ODPI-C installed)
#   - ctpublic.h:    /usr/include (FreeTDS)
#
# Strategy: ask pkg-config when available, then add well-known fallback -I
# paths. gcc silently ignores non-existent -I paths, so listing them is safe.
#
# All drivers are compiled unconditionally — missing runtime libraries are
# handled lazily by dlopen at runtime, not at build time. The canonical build
# environment is docker/Dockerfile, which ships every required header.
# ---------------------------------------------------------------------------

PKG_CONFIG := $(shell command -v pkg-config 2>/dev/null)

ifneq ($(PKG_CONFIG),)
  PKG_CFLAGS := \
    $(shell $(PKG_CONFIG) --cflags libpq      2>/dev/null) \
    $(shell $(PKG_CONFIG) --cflags sqlite3    2>/dev/null) \
    $(shell $(PKG_CONFIG) --cflags libmariadb 2>/dev/null) \
    $(shell $(PKG_CONFIG) --cflags freetds    2>/dev/null)
else
  PKG_CFLAGS :=
endif

FALLBACK_INCLUDES := \
    -I/usr/include/postgresql \
    -I/usr/include/mysql      \
    -I/usr/include/mariadb

EXTRA_INCLUDES := $(PKG_CFLAGS) $(FALLBACK_INCLUDES)

SRC_CORE    = kdbc_core.c
OBJ_CORE    = $(SRC_CORE:.c=.o)

SRC_DRIVERS = drivers/driver_sqlite.c \
              drivers/driver_postgres.c \
              drivers/driver_mariadb.c \
              drivers/driver_oracle.c \
              drivers/driver_mssql.c

OBJ_DRIVERS = $(SRC_DRIVERS:.c=.o)
OBJECTS     = $(OBJ_CORE) $(OBJ_DRIVERS)

CFLAGS = -Wall -Wextra -std=c11 -fPIC -Iinclude -g -O2 $(EXTRA_INCLUDES)

# ---------------------------------------------------------------------------
# Link flags
#
# - Windows uses LoadLibrary (kernel32), no -ldl.
# - POSIX needs -ldl for dlopen/dlsym and -lpthread for pthread_once. On
#   glibc >= 2.34 libpthread is a stub merged into libc, making -lpthread a
#   harmless no-op; on older glibc (e.g. Ubuntu 20.04 / glibc 2.31) it is
#   required so pthread_once resolves against libpthread.so.0.
# ---------------------------------------------------------------------------

ifeq ($(OS),Windows_NT)
  LDFLAGS =
else
  LDFLAGS = -ldl -lpthread
endif

LIB_STATIC  = libkdbc.a
LIB_SHARED  = libkdbc.so

TEST_SRC    = test/test_kdbc.c
TEST_BIN    = test/test_kdbc

.PHONY: all clean test lib

all: lib

lib: $(LIB_STATIC) $(LIB_SHARED)

$(LIB_STATIC): $(OBJECTS)
	ar rcs $@ $^

$(LIB_SHARED): $(OBJECTS)
	$(CC) -shared -o $@ $^ $(LDFLAGS)

%.o: %.c
	$(CC) $(CFLAGS) -c -o $@ $<

test: $(TEST_BIN)
	./$(TEST_BIN)

$(TEST_BIN): $(TEST_SRC) $(LIB_STATIC)
	$(CC) $(CFLAGS) -o $@ $< $(LIB_STATIC) $(LDFLAGS) -lm

clean:
	rm -f $(OBJECTS) $(LIB_STATIC) $(LIB_SHARED) $(TEST_BIN)
