#ifdef _WIN32
#define KEXTRACT_FIXTURE_EXPORT __declspec(dllexport)
#else
#define KEXTRACT_FIXTURE_EXPORT __attribute__((visibility("default")))
#endif

KEXTRACT_FIXTURE_EXPORT int fixture_dependency_value(void) {
    return 41;
}
