#!/usr/bin/env bash
# Fetch Windows x64 runtime DLLs needed by mingwX64 test.exe under Wine.
# All sources are official upstream; nothing is committed to the repo.
#   - MSYS2 mingw64 repo: postgres, mariadb, freetds, openssl, libiconv, gettext, winpthread
#   - sqlite.org: sqlite3.dll
#   - download.oracle.com: Oracle Instant Client (Windows x64)
#   - github.com/oracle/odpi: ODPI-C built from source with mingw cross-compiler
#
# Usage: fetch-mingw-dlls.sh <dest-dir>

set -euo pipefail

DEST="${1:?usage: $0 <dest-dir>}"
mkdir -p "$DEST"

MSYS_REPO="https://repo.msys2.org/mingw/mingw64"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "::group::Resolve MSYS2 latest package versions"
LISTING="$(curl -fsSL "$MSYS_REPO/")"
resolve() {
  local pkg="$1"
  echo "$LISTING" | grep -oE "${pkg}-[0-9][^\"]*-any\.pkg\.tar\.zst" \
    | sort -V | tail -1
}
fetch_msys() {
  local pkg="$1" file
  file="$(resolve "$pkg")"
  if [[ -z "$file" ]]; then
    echo "ERROR: cannot resolve $pkg in MSYS2 repo" >&2
    exit 1
  fi
  echo "  $file"
  curl -fsSL -o "$TMP/$file" "$MSYS_REPO/$file"
  mkdir -p "$TMP/x/$pkg"
  tar -I zstd -xf "$TMP/$file" -C "$TMP/x/$pkg"
}
echo "::endgroup::"

echo "::group::Download MSYS2 packages"
fetch_msys mingw-w64-x86_64-postgresql
fetch_msys mingw-w64-x86_64-libmariadbclient
fetch_msys mingw-w64-x86_64-freetds
fetch_msys mingw-w64-x86_64-openssl
fetch_msys mingw-w64-x86_64-libiconv
fetch_msys mingw-w64-x86_64-gettext-runtime
fetch_msys mingw-w64-x86_64-libwinpthread
# Transitive deps for libmariadb (curl/zstd/zlib SSL transport)
fetch_msys mingw-w64-x86_64-curl
fetch_msys mingw-w64-x86_64-gcc-libs
fetch_msys mingw-w64-x86_64-zstd
fetch_msys mingw-w64-x86_64-zlib
fetch_msys mingw-w64-x86_64-brotli
fetch_msys mingw-w64-x86_64-libidn2
fetch_msys mingw-w64-x86_64-libunistring
fetch_msys mingw-w64-x86_64-libpsl
fetch_msys mingw-w64-x86_64-libssh2
fetch_msys mingw-w64-x86_64-nghttp2
fetch_msys mingw-w64-x86_64-nghttp3
fetch_msys mingw-w64-x86_64-ngtcp2
echo "::endgroup::"

copy_if_present() {
  local src="$1"
  for f in $src; do
    [[ -e "$f" ]] || continue
    cp -v "$f" "$DEST/"
  done
}

echo "::group::Stage DLLs from MSYS2 packages"
# Postgres + OpenSSL + libintl + libiconv (libpq depends on these)
copy_if_present "$TMP/x/mingw-w64-x86_64-postgresql/mingw64/bin/libpq.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-openssl/mingw64/bin/libssl-*.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-openssl/mingw64/bin/libcrypto-*.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-libiconv/mingw64/bin/libiconv-*.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-gettext-runtime/mingw64/bin/libintl-*.dll"

# MariaDB / MySQL connector + plugins
copy_if_present "$TMP/x/mingw-w64-x86_64-libmariadbclient/mingw64/bin/libmariadb.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-libmariadbclient/mingw64/lib/mariadb/plugin/caching_sha2_password.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-libmariadbclient/mingw64/lib/mariadb/plugin/dialog.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-libmariadbclient/mingw64/lib/mariadb/plugin/sha256_password.dll"

# FreeTDS (MS SQL)
copy_if_present "$TMP/x/mingw-w64-x86_64-freetds/mingw64/bin/libsybdb-*.dll"

# mingw runtime + curl/zstd/zlib/brotli transitive set (libmariadb pulls these)
copy_if_present "$TMP/x/mingw-w64-x86_64-libwinpthread/mingw64/bin/libwinpthread-*.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-curl/mingw64/bin/libcurl-*.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-gcc-libs/mingw64/bin/libgcc_s_seh-*.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-gcc-libs/mingw64/bin/libstdc++-*.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-zstd/mingw64/bin/libzstd.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-zlib/mingw64/bin/zlib1.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-brotli/mingw64/bin/libbrotlicommon.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-brotli/mingw64/bin/libbrotlidec.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-libidn2/mingw64/bin/libidn2-*.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-libunistring/mingw64/bin/libunistring-*.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-libpsl/mingw64/bin/libpsl-*.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-libssh2/mingw64/bin/libssh2-*.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-nghttp2/mingw64/bin/libnghttp2-*.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-nghttp3/mingw64/bin/libnghttp3-*.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-ngtcp2/mingw64/bin/libngtcp2-*.dll"
copy_if_present "$TMP/x/mingw-w64-x86_64-ngtcp2/mingw64/bin/libngtcp2_crypto_ossl-*.dll"
echo "::endgroup::"

echo "::group::Download SQLite"
SQLITE_VER="3470200"
SQLITE_YEAR="2024"
curl -fsSL -o "$TMP/sqlite.zip" \
  "https://www.sqlite.org/${SQLITE_YEAR}/sqlite-dll-win-x64-${SQLITE_VER}.zip"
unzip -q -o "$TMP/sqlite.zip" -d "$TMP/sqlite"
cp -v "$TMP/sqlite/sqlite3.dll" "$DEST/"
echo "::endgroup::"

echo "::group::Download Oracle Instant Client (Windows x64)"
# Use 21c Instant Client — 23c Windows client + Wine triggers ORA-28041
# (auth-protocol internal error) against XE 21c/11g; 21c uses the older
# auth flow that Wine handles correctly.
curl -fsSL -o "$TMP/oic.zip" \
  "https://download.oracle.com/otn_software/nt/instantclient/2120000/instantclient-basiclite-windows.x64-21.20.0.0.0dbru.zip"
unzip -q -o "$TMP/oic.zip" -d "$TMP/oic"
OIC_DIR="$(find "$TMP/oic" -maxdepth 1 -type d -name 'instantclient_*' | head -1)"
echo "  Instant Client: $OIC_DIR"
copy_if_present "$OIC_DIR/oci.dll"
copy_if_present "$OIC_DIR/oraociicus.dll"
copy_if_present "$OIC_DIR/oraociei*.dll"
copy_if_present "$OIC_DIR/orannzsbb*.dll"
copy_if_present "$OIC_DIR/oraocci*.dll"
copy_if_present "$OIC_DIR/orasql*.dll"
copy_if_present "$OIC_DIR/ociw32.dll"
copy_if_present "$OIC_DIR/ocijdbc*.dll"
copy_if_present "$OIC_DIR/orannz*.dll"
copy_if_present "$OIC_DIR/oranfsodm*.dll"
copy_if_present "$OIC_DIR/oraons.dll"
echo "::endgroup::"

echo "::group::Build ODPI-C as Windows DLL (mingw cross-compile)"
git clone --depth 1 --branch v5.6.4 https://github.com/oracle/odpi.git "$TMP/odpi"
# ODPI's Makefile doesn't ship a mingw target — build manually.
# Public headers in include/, sources in src/, no external deps at compile time.
ODPI_SRC="$TMP/odpi"
ODPI_OBJ="$TMP/odpi-obj"
mkdir -p "$ODPI_OBJ"
CC=x86_64-w64-mingw32-gcc
for c in "$ODPI_SRC"/src/*.c; do
  obj="$ODPI_OBJ/$(basename "${c%.c}").o"
  "$CC" -c -O2 -fno-strict-aliasing -DBUILD_DLL \
    -I"$ODPI_SRC/include" -I"$ODPI_SRC/src" \
    -o "$obj" "$c"
done
"$CC" -shared -o "$DEST/odpic.dll" "$ODPI_OBJ"/*.o \
  -Wl,--out-implib,"$TMP/libodpic.dll.a" \
  -static-libgcc
echo "  built odpic.dll"
echo "::endgroup::"

echo "::group::Final DLL inventory in $DEST"
ls -la "$DEST"/*.dll
echo "::endgroup::"
