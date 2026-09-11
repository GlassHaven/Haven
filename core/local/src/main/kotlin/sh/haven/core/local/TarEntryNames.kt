package sh.haven.core.local

/**
 * Tar entry-name normalization for the rootfs importer, kept pure and
 * Android-free so it is unit-testable on the JVM.
 *
 * Not every producer writes entries the way GNU tar does (`dir/`,
 * `dir/file`). Archives built from some trees carry directories — and
 * sometimes regular-file entries that can only be directories — named
 * with a trailing `/.` or `/`:
 *
 * ```
 * usr/lib/node_modules/gopd/.
 * ```
 *
 * The old importer passed that name straight to `FileOutputStream`,
 * which resolves `gopd/.` to the existing `gopd` directory and fails
 * with `EISDIR` — aborting the whole import (#546).
 */
internal object TarEntryNames {

    /**
     * Collapse a trailing `/` or `/.` off [raw], repeatedly, and report
     * whether the entry name marked a directory. `a/./` → `a`,
     * `x/y/gopd/.` → `x/y/gopd`; a bare `.` (the archive root, as GNU
     * tar writes with `-C dir .`) is left alone — callers already treat
     * it as a no-op directory entry.
     *
     * Returns `(name, marksDirectory)`. `marksDirectory` is true when
     * the raw name ended in `/` or `/.` — such an entry names a
     * directory even if its typeflag says regular file, and callers
     * must not open it for writing.
     */
    fun normalize(raw: String): Pair<String, Boolean> {
        var name = raw
        var marksDirectory = false
        while (true) {
            name = when {
                name.endsWith("/") -> {
                    marksDirectory = true
                    name.dropLast(1)
                }
                name.endsWith("/.") -> {
                    marksDirectory = true
                    name.dropLast(2)
                }
                else -> return name to marksDirectory
            }
        }
    }
}