package com.ditarector.diligent.sync.conflict

/**
 * Names for the copy a conflict leaves next to the local file (SPEC A.7):
 * `<name> (from <Device>).<ext>`, in the same folder.
 *
 * The extension is what follows the last dot, so editors still recognise
 * the copy (`main (from Laptop).kt`, `archive.tar (from Laptop).gz`). Dots at
 * the start of a name do not start an extension: `.bashrc` becomes
 * `.bashrc (from Laptop)` and `.env.local` becomes `.env (from Laptop).local`.
 * A name ending in a dot, or without one, gets the suffix at the end.
 */
public object ConflictNames {
    private const val FORBIDDEN = "/\\:*?\"<>|"

    /** The copy's path for [path] received from the device called [deviceName]. */
    public fun copyName(path: String, deviceName: String): String = build(path, "from ${clean(deviceName)}")

    /**
     * Like [copyName], but if that path already [exists] (an earlier conflict
     * not yet resolved) numbers it: `a (from Laptop 2).txt`, `... 3` and so on,
     * so no earlier copy is overwritten.
     */
    public fun uniqueCopyName(path: String, deviceName: String, exists: (String) -> Boolean): String {
        val device = clean(deviceName)
        var candidate = build(path, "from $device")
        var n = 2
        while (exists(candidate)) candidate = build(path, "from $device ${n++}")
        return candidate
    }

    private fun build(path: String, label: String): String {
        val slash = path.lastIndexOf('/')
        val dir = path.substring(0, slash + 1)
        val name = path.substring(slash + 1)
        val leadingDots = name.takeWhile { it == '.' }.length
        val dot = name.lastIndexOf('.')
        val hasExtension = dot >= leadingDots && dot < name.length - 1
        return if (hasExtension) {
            "$dir${name.substring(0, dot)} ($label)${name.substring(dot)}"
        } else {
            "$dir$name ($label)"
        }
    }

    /** Device names are user-editable; keep them from adding folders or characters some file systems reject. */
    private fun clean(deviceName: String): String {
        val cleaned = deviceName.map { if (it in FORBIDDEN || it.isISOControl()) '_' else it }.joinToString("").trim().trim('.')
        return cleaned.ifEmpty { "other device" }
    }
}
