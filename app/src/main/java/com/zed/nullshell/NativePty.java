package com.zed.nullshell;

/**
 * A real pseudo-terminal for a session, from the small native library libnsterm.so (app/src/main/cpp/nsterm.c).
 * When the library is not in the APK, {@link #AVAILABLE} is false and {@link KaliSession} uses the older approach
 * (script(1) inside the guest), so the app still works.
 */
final class NativePty {
    private NativePty() { }

    static final boolean AVAILABLE;
    static {
        boolean ok;
        try { System.loadLibrary("nsterm"); ok = true; } catch (Throwable t) { ok = false; }
        AVAILABLE = ok;
    }

    /** Runs cmd on a new terminal. Returns {masterFd, pid}; throws RuntimeException on failure. */
    static native int[] spawn(String cmd, String[] argv, String[] env, String cwd, int rows, int cols);
    static native void setSize(int fd, int rows, int cols);
    /** Bytes read, or -1 at the end of the session. */
    static native int read(int fd, byte[] buf, int off, int len);
    /** 0 on success, -1 on error. */
    static native int write(int fd, byte[] buf, int off, int len);
    /** Blocks until the process ends: exit status, or minus the signal number. */
    static native int waitFor(int pid);
    /** Signals the process group of the session. */
    static native void kill(int pid, int sig);
    static native void close(int fd);
}
