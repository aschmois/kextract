#include <stdio.h>
#include <stdlib.h>

#ifdef _WIN32
#include <windows.h>
#define KEXTRACT_FIXTURE_EXPORT __declspec(dllexport)
#else
#define KEXTRACT_FIXTURE_EXPORT __attribute__((visibility("default")))
#endif

int fixture_dependency_value(void);

static void record_load(void) {
    const char *counter_path = getenv("KEXTRACT_FIXTURE_LOAD_COUNTER");
    if (counter_path == NULL) {
        return;
    }

    FILE *counter = fopen(counter_path, "ab");
    if (counter != NULL) {
        fputs("loaded\n", counter);
        fclose(counter);
    }
}

#ifdef _WIN32
BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    (void) instance;
    (void) reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        record_load();
    }
    return TRUE;
}
#else
__attribute__((constructor)) static void on_load(void) {
    record_load();
}
#endif

KEXTRACT_FIXTURE_EXPORT int fixture_first_downcall(void) {
    return fixture_dependency_value() + 1;
}
