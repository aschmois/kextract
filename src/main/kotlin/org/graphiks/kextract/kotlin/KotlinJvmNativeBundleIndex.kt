package org.graphiks.kextract.kotlin

import org.graphiks.kextract.pipeline.Options
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

internal data class KotlinJvmNativeBundleResource(
    val relativePath: String,
    val sha256: String,
)

internal data class KotlinJvmNativePlatformBundle(
    val id: String,
    val resources: List<KotlinJvmNativeBundleResource>,
    internal val libraryPaths: Map<String, String>,
)

internal data class KotlinJvmNativeBundleIndex(
    val platforms: List<KotlinJvmNativePlatformBundle>,
) {
    fun platform(id: String): KotlinJvmNativePlatformBundle? = platforms.firstOrNull { it.id == id }

    fun resourcePath(platform: String, library: Options.Library): String? =
        if (library.specKind == Options.Library.SpecKind.NAME) {
            platform(platform)?.libraryPaths?.get(library.libSpec)
        } else {
            null
        }

    companion object {
        val PLATFORMS = listOf(
            "darwin-aarch64",
            "darwin-x86-64",
            "linux-aarch64",
            "linux-x86-64",
            "win32-x86-64",
        )

        fun scan(resourcesRoot: Path, libraries: List<Options.Library>): KotlinJvmNativeBundleIndex {
            val platforms = PLATFORMS.mapNotNull { platform ->
                val platformRoot = resourcesRoot.resolve(platform)
                if (!platformRoot.isDirectory()) return@mapNotNull null

                val paths = Files.walk(platformRoot).use { stream ->
                    stream.filter(Path::isRegularFile)
                        .map { path -> platformRoot.relativize(path) }
                        .sorted(compareBy { it.toPortablePath() })
                        .toList()
                }
                val resources = paths.map { relativePath ->
                    KotlinJvmNativeBundleResource(
                        relativePath = relativePath.toPortablePath(),
                        sha256 = sha256(platformRoot.resolve(relativePath)),
                    )
                }
                val libraryPaths = libraries
                    .filter { it.specKind == Options.Library.SpecKind.NAME }
                    .associate { library ->
                        val mappedName = mapLibraryFileName(platform, library.libSpec)
                        val matches = resources.filter { resource ->
                            resource.relativePath.substringAfterLast('/') == mappedName
                        }
                        require(matches.size <= 1) {
                            "Multiple resources named $mappedName found below $platformRoot"
                        }
                        library.libSpec to matches.singleOrNull()?.relativePath
                    }
                    .filterValues { it != null }
                    .mapValues { (_, value) -> checkNotNull(value) }

                KotlinJvmNativePlatformBundle(platform, resources, libraryPaths)
            }
            return KotlinJvmNativeBundleIndex(platforms)
        }

        fun mapLibraryFileName(platform: String, logicalName: String): String = when {
            platform.startsWith("darwin-") -> "lib$logicalName.dylib"
            platform.startsWith("linux-") -> "lib$logicalName.so"
            platform.startsWith("win32-") -> "$logicalName.dll"
            else -> error("Unsupported JVM native platform: $platform")
        }

        private fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        private fun Path.toPortablePath(): String = toString().replace('\\', '/')
    }
}
