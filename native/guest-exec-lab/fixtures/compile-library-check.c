#include "sqlite3.h"
#include <stdio.h>

int main(void) {
    sqlite3 *db = NULL;
    sqlite3_stmt *query = NULL;
    if (sqlite3_libversion_number() != SQLITE_VERSION_NUMBER ||
        sqlite3_open(":memory:", &db) != SQLITE_OK ||
        sqlite3_prepare_v2(db, "SELECT 6 * 7", -1, &query, NULL) != SQLITE_OK ||
        sqlite3_step(query) != SQLITE_ROW || sqlite3_column_int(query, 0) != 42)
        return 1;
    sqlite3_finalize(query);
    if (sqlite3_close(db) != SQLITE_OK) return 1;
    puts("MD_LIBRARY_OK 42");
    return 0;
}
