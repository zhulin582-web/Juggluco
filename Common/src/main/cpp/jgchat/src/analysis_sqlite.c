// SQLite's public-domain C amalgamation is compiled privately into this module.
// Hidden symbols keep its configuration/heap limit separate from Android's
// system SQLite and other libraries. No loadable extensions or temporary files.
#define SQLITE_API __attribute__((visibility("hidden")))
#define SQLITE_OMIT_LOAD_EXTENSION 1
#define SQLITE_OMIT_SHARED_CACHE 1
#define SQLITE_THREADSAFE 1
#define SQLITE_TEMP_STORE 3
#define SQLITE_ENABLE_MATH_FUNCTIONS 1
#define SQLITE_ENABLE_PERCENTILE 1
#define SQLITE_TRUSTED_SCHEMA 0
#define SQLITE_MAX_ALLOCATION_SIZE 16777216
#define SQLITE_PRINTF_PRECISION_LIMIT 10000
#include "../vendor/sqlite/sqlite3.c"
