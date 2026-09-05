package com.twig.core

/**
 * Global file system registry: scheme -> FileSystem.
 *
 * Each module (fs-local / fs-archive / fs-network ...) registers its implementation
 * during initialization; once the UI receives an [XFile], it uses [of] to find the
 * matching FileSystem to operate on it. This keeps `:app` from depending directly
 * on any concrete implementation, enabling "mount on demand, package on demand".
 */
object FsRegistry {

    /**
     * ★ Must be thread-safe: registration happens on IO threads (expanding server
     * nodes, mounting restic, identifying git repositories all run on
     * `Dispatchers.IO`, and both panes can expand different servers simultaneously),
     * while reads from [of] happen on the main thread (taking FileSystem row by row
     * while building the tree). A bare HashMap here is a real data race.
     * Use synchronizedMap rather than ConcurrentHashMap: we must preserve
     * LinkedHashMap's insertion order, since [all] means "list sources in
     * registration order".
     */
    private val systems: MutableMap<String, FileSystem> =
        java.util.Collections.synchronizedMap(LinkedHashMap())

    fun register(fs: FileSystem) {
        systems[fs.scheme] = fs
    }

    /**
     * Unregister a scheme and return the removed implementation (null if none);
     * the caller is responsible for closing the connection.
     *
     * Used on the path "server config changed, the next expansion must reconnect
     * with the new config": the scheme is derived deterministically from the
     * connection label, so changing only the password leaves both the label and
     * scheme unchanged. Without unregistering, the "if already registered, reuse"
     * line in [Connections.ensure] keeps handing back the instance built with the
     * **old** config, which surfaces as "I changed the password / host key but
     * it has no effect until I restart the app".
     */
    fun unregister(scheme: String): FileSystem? = systems.remove(scheme)

    fun of(scheme: String): FileSystem =
        systems[scheme] ?: throw FsException("No file system registered for scheme: $scheme")

    fun of(file: XFile): FileSystem = of(file.scheme)

    /** All registered file systems; used by the sidebar to list accessible sources. */
    fun all(): List<FileSystem> = synchronized(systems) { systems.values.toList() }
}
