package sh.haven.core.local

/**
 * JNI bridge to the native PTY library.
 * Provides forkpty(), terminal resize, and waitpid().
 */
object PtyBridge {

    init {
        System.loadLibrary("pty_bridge")
    }

    /**
     * Fork a child process with a pseudoterminal.
     * @param raw raw-mode pty (cfmakeraw) instead of the default cooked
     *   termios — used by the UML guest console, which implements its own
     *   tty semantics inside the guest (a cooked host pty turns \r into \n
     *   and ctrl-c into a SIGINT to the UML kernel itself)
     * @return int[2]: [masterFd, childPid] on success, [-1, errno] on failure
     */
    external fun nativeForkPty(
        cmd: String,
        args: Array<String>,
        env: Array<String>,
        rows: Int,
        cols: Int,
        raw: Boolean,
    ): IntArray

    /**
     * Set terminal size on a PTY master fd.
     * @return 0 on success, -1 on failure
     */
    external fun nativeSetSize(fd: Int, rows: Int, cols: Int): Int

    /**
     * Wait for child process to exit.
     * @return exit status, or -1 on error
     */
    external fun nativeWaitPid(pid: Int): Int
}
