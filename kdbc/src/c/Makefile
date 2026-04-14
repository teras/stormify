# ---------------------------------------------------------------------------
# Cross-compilation support
#
# TARGET selects the toolchain:
#   native  (default) — host gcc/clang, produces binaries for the host OS
#   linux   — x86_64-linux-gnu-gcc, cross-compiles Linux x64 from non-Linux hosts
#   mingw   — x86_64-w64-mingw32-gcc, produces Windows x64 binaries
#   arm64   — aarch64-linux-gnu-gcc, produces Linux ARM64 binaries
#   ios     — Xcode clang, produces iOS device (arm64) binaries
#   ios-sim — Xcode clang, produces iOS simulator (arm64) binaries
#
# BUILDDIR places all outputs (objects, libraries) in a separate directory
# so multiple targets can coexist without cleaning between builds.
#
# Examples:
#   make lib                                        # native host
#   make TARGET=linux BUILDDIR=build-linux lib      # Linux x64 (cross-compile)
#   make TARGET=mingw BUILDDIR=build-mingw lib      # Windows x64
#   make TARGET=arm64 BUILDDIR=build-arm64 lib      # Linux ARM64
# ---------------------------------------------------------------------------

TARGET   ?= native
BUILDDIR ?= .

ifeq ($(TARGET),mingw)
  CC       = x86_64-w64-mingw32-gcc
  AR       = x86_64-w64-mingw32-ar
  LDFLAGS  = -lpthread
  LIB_SHARED = $(BUILDDIR)/libkdbc.dll
else ifeq ($(TARGET),linux)
  CC       = x86_64-linux-gnu-gcc
  AR       = x86_64-linux-gnu-ar
  LDFLAGS  = -ldl -lpthread
  LIB_SHARED = $(BUILDDIR)/libkdbc.so
else ifeq ($(TARGET),arm64)
  CC       = aarch64-linux-gnu-gcc
  AR       = aarch64-linux-gnu-ar
  LDFLAGS  = -ldl -lpthread
  LIB_SHARED = $(BUILDDIR)/libkdbc.so
  # Disable outline atomics — GCC emits calls to __aarch64_ldadd* helpers
  # that live in libgcc, but Kotlin/Native links with its own LLVM runtime
  # which does not provide them.
  EXTRA_CFLAGS = -mno-outline-atomics
else ifeq ($(TARGET),ios-sim)
  CC       = $(shell xcrun --sdk iphonesimulator --find clang)
  AR       = ar
  LDFLAGS  = -ldl
  LIB_SHARED = $(BUILDDIR)/libkdbc.dylib
  EXTRA_CFLAGS = -target arm64-apple-ios-simulator -isysroot $(shell xcrun --sdk iphonesimulator --show-sdk-path)
else ifeq ($(TARGET),ios)
  CC       = $(shell xcrun --sdk iphoneos --find clang)
  AR       = ar
  LDFLAGS  = -ldl
  LIB_SHARED = $(BUILDDIR)/libkdbc.dylib
  EXTRA_CFLAGS = -target arm64-apple-ios -isysroot $(shell xcrun --sdk iphoneos --show-sdk-path)
else
  CC       = gcc
  AR       = ar
  LIB_SHARED = $(BUILDDIR)/libkdbc.so
  ifeq ($(OS),Windows_NT)
    LDFLAGS =
  else
    LDFLAGS = -ldl -lpthread
  endif
endif

# ---------------------------------------------------------------------------
# Database client headers
#
# All database client headers are vendored under include/vendor/<driver>/
# so that cross-compilation works without system-installed dev packages.
# The headers define stable C APIs and are platform-independent.
#
# To update vendored headers from system packages, run:
#   make vendor-headers
#
# All drivers are compiled unconditionally — missing runtime libraries are
# handled lazily by dlopen at runtime, not at build time.
# ---------------------------------------------------------------------------

VENDOR_INCLUDES := \
    -Iinclude/vendor/sqlite    \
    -Iinclude/vendor/postgres  \
    -Iinclude/vendor/mariadb   \
    -Iinclude/vendor/freetds   \
    -Iinclude/vendor/oracle

SRC_CORE    = kdbc_core.c
SRC_DRIVERS = drivers/driver_sqlite.c \
              drivers/driver_postgres.c \
              drivers/driver_mariadb.c \
              drivers/driver_oracle.c \
              drivers/driver_mssql.c

OBJ_CORE    = $(addprefix $(BUILDDIR)/,$(SRC_CORE:.c=.o))
OBJ_DRIVERS = $(addprefix $(BUILDDIR)/,$(SRC_DRIVERS:.c=.o))
OBJECTS     = $(OBJ_CORE) $(OBJ_DRIVERS)

CFLAGS = -Wall -Wextra -std=c11 -fPIC -Iinclude -g -O2 $(VENDOR_INCLUDES) $(EXTRA_CFLAGS)

LIB_STATIC  = $(BUILDDIR)/libkdbc.a

TEST_SRC    = test/test_kdbc.c
TEST_BIN    = $(BUILDDIR)/test/test_kdbc

.PHONY: all clean test lib docs vendor-headers

all: lib

lib: $(LIB_STATIC) $(LIB_SHARED)

$(LIB_STATIC): $(OBJECTS)
	$(AR) rcs $@ $^

$(LIB_SHARED): $(OBJECTS)
	$(CC) -shared -o $@ $^ $(LDFLAGS)

$(BUILDDIR)/%.o: %.c
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) -c -o $@ $<

test: $(TEST_BIN)
	./$(TEST_BIN)

$(TEST_BIN): $(TEST_SRC) $(LIB_STATIC)
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) -o $@ $< $(LIB_STATIC) $(LDFLAGS) -lm

docs:
	doxygen Doxyfile

clean:
	rm -f $(OBJECTS) $(LIB_STATIC) $(LIB_SHARED) $(TEST_BIN)
ifneq ($(BUILDDIR),.)
	rm -rf $(BUILDDIR)
endif

# ---------------------------------------------------------------------------
# Refresh vendored database client headers from system packages.
# Run manually when upgrading a database client library version.
# ---------------------------------------------------------------------------

VENDOR_DIR = include/vendor

vendor-headers:
	rm -rf $(VENDOR_DIR)/sqlite   && mkdir -p $(VENDOR_DIR)/sqlite
	rm -rf $(VENDOR_DIR)/postgres && mkdir -p $(VENDOR_DIR)/postgres
	rm -rf $(VENDOR_DIR)/mariadb  && mkdir -p $(VENDOR_DIR)/mariadb
	rm -rf $(VENDOR_DIR)/freetds  && mkdir -p $(VENDOR_DIR)/freetds
	rm -rf $(VENDOR_DIR)/oracle   && mkdir -p $(VENDOR_DIR)/oracle
	cp /usr/include/sqlite3.h                          $(VENDOR_DIR)/sqlite/
	cp /usr/include/libpq-fe.h /usr/include/postgres_ext.h $(VENDOR_DIR)/postgres/
	mkdir -p $(VENDOR_DIR)/mariadb/mysql
	cp /usr/include/mysql/*.h                          $(VENDOR_DIR)/mariadb/mysql/
	cp /usr/include/sybdb.h /usr/include/sybfront.h /usr/include/tds_sysdep_public.h $(VENDOR_DIR)/freetds/
	cp /usr/include/dpi.h                              $(VENDOR_DIR)/oracle/
	@echo "Vendored headers refreshed from system packages."
