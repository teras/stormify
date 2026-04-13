/*
 * KDBC Native - Portable dynamic library loading
 *
 * Wraps dlopen/dlsym (POSIX) and LoadLibrary/GetProcAddress (Windows)
 * behind a common API. Each driver includes this instead of <dlfcn.h>.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef KDBC_DL_H
#define KDBC_DL_H

#ifdef _WIN32

#include <windows.h>

typedef HMODULE kdbc_lib_handle;

static inline kdbc_lib_handle kdbc_dl_open(const char *name, int flags) {
    (void)flags; /* Windows LoadLibrary doesn't have RTLD_* equivalents */
    return LoadLibraryA(name);
}

static inline void *kdbc_dl_sym(kdbc_lib_handle lib, const char *symbol) {
    return (void *)GetProcAddress(lib, symbol);
}

static inline void kdbc_dl_close(kdbc_lib_handle lib) {
    if (lib) FreeLibrary(lib);
}

/* POSIX flags - defined as no-ops on Windows so drivers compile unchanged */
#ifndef RTLD_LAZY
#define RTLD_LAZY 0
#endif

#else /* POSIX */

#include <dlfcn.h>

typedef void *kdbc_lib_handle;

static inline kdbc_lib_handle kdbc_dl_open(const char *name, int flags) {
    return dlopen(name, flags);
}

static inline void *kdbc_dl_sym(kdbc_lib_handle lib, const char *symbol) {
    return dlsym(lib, symbol);
}

static inline void kdbc_dl_close(kdbc_lib_handle lib) {
    if (lib) dlclose(lib);
}

#endif

/*
 * Library name patterns per platform.
 * Usage: kdbc_dl_open(KDBC_LIBNAME("sqlite3", "0"))
 *   Linux:   "libsqlite3.so.0"
 *   macOS:   "libsqlite3.0.dylib"
 *   Windows: "sqlite3.dll"
 *
 * Some Windows libraries keep the "lib" prefix (e.g. libpq.dll, libmariadb.dll).
 * Use KDBC_LIBNAME_LIBPREFIX for those.
 */
#ifdef _WIN32
  #define KDBC_LIBNAME(base, ver) base ".dll"
  #define KDBC_LIBNAME_NOVER(base) base ".dll"
  #define KDBC_LIBNAME_LIBPREFIX(base) "lib" base ".dll"
  #define KDBC_LIBNAME_LIBVER(base, ver) "lib" base "-" ver ".dll"
#elif defined(__APPLE__)
  #define KDBC_LIBNAME(base, ver) "lib" base "." ver ".dylib"
  #define KDBC_LIBNAME_NOVER(base) "lib" base ".dylib"
  #define KDBC_LIBNAME_LIBPREFIX(base) "lib" base ".dylib"
  #define KDBC_LIBNAME_LIBVER(base, ver) "lib" base "-" ver ".dylib"
#else
  #define KDBC_LIBNAME(base, ver) "lib" base ".so." ver
  #define KDBC_LIBNAME_NOVER(base) "lib" base ".so"
  #define KDBC_LIBNAME_LIBPREFIX(base) "lib" base ".so"
  #define KDBC_LIBNAME_LIBVER(base, ver) "lib" base "-" ver ".so"
#endif

#endif /* KDBC_DL_H */
