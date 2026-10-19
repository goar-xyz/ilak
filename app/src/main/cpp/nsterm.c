/*
 * NullShell native PTY helper (MIT, same as the app). Written for NullShell from the POSIX pty interfaces.
 *
 * Java cannot call fork(), so the app could not give PRoot a real terminal and ran script(1) inside the guest instead.
 * This library opens a pseudo-terminal, forks, and runs the command with the terminal as its stdin/stdout/stderr and
 * controlling terminal. The window size is set with an ioctl on the master side, so no helper process is needed.
 *
 * The child between fork() and exec() only calls async-signal-safe functions; everything it needs is built beforehand.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define BUF_SIZE 16384

static void throw_re(JNIEnv *env, const char *msg) {
    jclass c = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (c) (*env)->ThrowNew(env, c, msg);
}

static void set_size(int fd, int rows, int cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof ws);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ioctl(fd, TIOCSWINSZ, &ws);
}

static void child_write(const char *s) {
    size_t n = strlen(s);
    while (n > 0) {
        ssize_t w = write(2, s, n);
        if (w <= 0) return;
        s += w;
        n -= (size_t) w;
    }
}

static void close_inherited(void) {
#ifdef __NR_close_range
    if (syscall(__NR_close_range, 3u, ~0u, 0) == 0) return;
#endif
    long max = sysconf(_SC_OPEN_MAX);
    if (max < 0 || max > 4096) max = 4096;
    for (int fd = 3; fd < max; fd++) close(fd);
}

static char **copy_strings(JNIEnv *env, jobjectArray arr, int *count) {
    jsize n = arr ? (*env)->GetArrayLength(env, arr) : 0;
    char **out = calloc((size_t) n + 1, sizeof(char *));
    if (!out) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        const char *u = js ? (*env)->GetStringUTFChars(env, js, NULL) : NULL;
        out[i] = strdup(u ? u : "");
        if (u) (*env)->ReleaseStringUTFChars(env, js, u);
        if (js) (*env)->DeleteLocalRef(env, js);
        if (!out[i]) { *count = i; return out; }
    }
    *count = n;
    return out;
}

static void free_strings(char **v) {
    if (!v) return;
    for (char **p = v; *p; p++) free(*p);
    free(v);
}

/** Returns int[]{masterFd, pid}. Throws RuntimeException when the terminal or the fork fails. */
JNIEXPORT jintArray JNICALL
Java_com_zed_nullshell_NativePty_spawn(JNIEnv *env, jclass clazz, jstring jcmd, jobjectArray jargv,
                                       jobjectArray jenv, jstring jcwd, jint rows, jint cols) {
    (void) clazz;
    int argc = 0, envc = 0;
    char **argv = copy_strings(env, jargv, &argc);
    char **envp = copy_strings(env, jenv, &envc);
    const char *cmd = (*env)->GetStringUTFChars(env, jcmd, NULL);
    const char *cwd = (*env)->GetStringUTFChars(env, jcwd, NULL);
    jintArray result = NULL;
    const char *err = NULL;
    int master = -1;

    if (!argv || !envp || !cmd || !cwd) { err = "out of memory"; goto done; }

    master = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) { err = "cannot open /dev/ptmx"; goto done; }

    char slave_name[64];
    if (grantpt(master) != 0 || unlockpt(master) != 0 || ptsname_r(master, slave_name, sizeof slave_name) != 0) {
        err = "grantpt/unlockpt/ptsname_r failed";
        goto done;
    }

    struct termios t;
    if (tcgetattr(master, &t) == 0) {
        t.c_iflag |= IUTF8;                 /* correct backspace handling for UTF-8 input */
        t.c_iflag &= ~(IXON | IXOFF);       /* Ctrl+S must not freeze the screen */
        tcsetattr(master, TCSANOW, &t);
    }
    set_size(master, rows, cols);

    pid_t pid = fork();
    if (pid < 0) { err = "fork failed"; goto done; }

    if (pid == 0) {
        /* child: async-signal-safe calls only */
        sigset_t all;
        sigfillset(&all);
        sigprocmask(SIG_UNBLOCK, &all, NULL);
        struct sigaction dfl;
        memset(&dfl, 0, sizeof dfl);
        dfl.sa_handler = SIG_DFL;
        for (int s = 1; s < 32; s++) if (s != SIGKILL && s != SIGSTOP) sigaction(s, &dfl, NULL);

        setsid();
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(126);
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        close_inherited();                  /* also closes the master and the original slave descriptor */

        if (chdir(cwd) != 0) child_write("nullshell: cannot enter the start directory\r\n");
        execve(cmd, argv, envp);
        child_write("nullshell: cannot run ");
        child_write(cmd);
        child_write("\r\n");
        _exit(127);
    }

    jint out[2] = { master, (jint) pid };
    result = (*env)->NewIntArray(env, 2);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, 2, out);
    master = -1;                            /* now owned by the caller */

done:
    if (master >= 0) close(master);
    if (cmd) (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    free_strings(argv);
    free_strings(envp);
    if (err) throw_re(env, err);
    return result;
}

JNIEXPORT void JNICALL
Java_com_zed_nullshell_NativePty_setSize(JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    (void) env; (void) clazz;
    if (fd >= 0) set_size(fd, rows, cols);
}

/** Bytes read, or -1 at the end of the session (the slave side closed, which reads as EIO on Linux). */
JNIEXPORT jint JNICALL
Java_com_zed_nullshell_NativePty_read(JNIEnv *env, jclass clazz, jint fd, jbyteArray arr, jint off, jint len) {
    (void) clazz;
    char buf[BUF_SIZE];
    if (len > BUF_SIZE) len = BUF_SIZE;
    if (len <= 0) return 0;
    for (;;) {
        ssize_t n = read(fd, buf, (size_t) len);
        if (n > 0) {
            (*env)->SetByteArrayRegion(env, arr, off, (jsize) n, (const jbyte *) buf);
            return (jint) n;
        }
        if (n == 0) return -1;
        if (errno == EINTR) continue;
        return -1;
    }
}

/** Writes everything or fails; returns 0 on success, -1 on error. */
JNIEXPORT jint JNICALL
Java_com_zed_nullshell_NativePty_write(JNIEnv *env, jclass clazz, jint fd, jbyteArray arr, jint off, jint len) {
    (void) clazz;
    char buf[BUF_SIZE];
    while (len > 0) {
        jint chunk = len > BUF_SIZE ? BUF_SIZE : len;
        (*env)->GetByteArrayRegion(env, arr, off, chunk, (jbyte *) buf);
        int done = 0;
        while (done < chunk) {
            ssize_t w = write(fd, buf + done, (size_t) (chunk - done));
            if (w < 0) {
                if (errno == EINTR || errno == EAGAIN) continue;
                return -1;
            }
            done += (int) w;
        }
        off += chunk;
        len -= chunk;
    }
    return 0;
}

/** Exit status, or minus the signal number when the process was killed by a signal. */
JNIEXPORT jint JNICALL
Java_com_zed_nullshell_NativePty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    int status = 0;
    for (;;) {
        pid_t r = waitpid((pid_t) pid, &status, 0);
        if (r < 0 && errno == EINTR) continue;
        if (r < 0) return -1;
        break;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}

/** Signals the whole session: the child is its own process group leader after setsid(). */
JNIEXPORT void JNICALL
Java_com_zed_nullshell_NativePty_kill(JNIEnv *env, jclass clazz, jint pid, jint sig) {
    (void) env; (void) clazz;
    if (pid > 1) {
        kill(-(pid_t) pid, sig);
        kill((pid_t) pid, sig);
    }
}

JNIEXPORT void JNICALL
Java_com_zed_nullshell_NativePty_close(JNIEnv *env, jclass clazz, jint fd) {
    (void) env; (void) clazz;
    if (fd >= 0) close(fd);
}
