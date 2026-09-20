#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <errno.h>
#include <termios.h>
#include <android/log.h>

#define TAG "PtyBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/*
 * Fork a child process with a pseudoterminal.
 *
 * raw != 0 puts the pty in raw mode (cfmakeraw) instead of the default
 * cooked termios. The UML console needs this: the guest kernel implements
 * its own tty semantics, so a cooked host pty double-processes input —
 * ICRNL turns Haven's \r into \n (Enter never submits inside the agent
 * TUI) and ISIG turns ctrl-c into a SIGINT to the UML kernel process
 * itself, killing the whole guest.
 *
 * Returns int[2]: [masterFd, childPid] on success, [-1, errno] on failure.
 */
JNIEXPORT jintArray JNICALL
Java_sh_haven_core_local_PtyBridge_nativeForkPty(
    JNIEnv *env, jclass cls,
    jstring jCmd, jobjectArray jArgs, jobjectArray jEnvVars,
    jint rows, jint cols, jboolean jRaw)
{
    jintArray result = (*env)->NewIntArray(env, 2);
    jint buf[2];

    const char *cmd = (*env)->GetStringUTFChars(env, jCmd, NULL);
    if (!cmd) {
        buf[0] = -1; buf[1] = ENOMEM;
        (*env)->SetIntArrayRegion(env, result, 0, 2, buf);
        return result;
    }

    /* Build argv */
    int argc = (*env)->GetArrayLength(env, jArgs);
    char **argv = calloc(argc + 1, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, jArgs, i);
        argv[i] = (char *)(*env)->GetStringUTFChars(env, s, NULL);
    }
    argv[argc] = NULL;

    /* Build envp */
    int envc = (*env)->GetArrayLength(env, jEnvVars);
    char **envp = calloc(envc + 1, sizeof(char *));
    for (int i = 0; i < envc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, jEnvVars, i);
        envp[i] = (char *)(*env)->GetStringUTFChars(env, s, NULL);
    }
    envp[envc] = NULL;

    /* Set initial terminal size */
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = rows;
    ws.ws_col = cols;

    int masterFd;
    struct termios rawTio;
    if (jRaw) {
        cfmakeraw(&rawTio);
    }
    pid_t pid = forkpty(&masterFd, NULL, jRaw ? &rawTio : NULL, &ws);

    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        buf[0] = -1; buf[1] = errno;
    } else if (pid == 0) {
        /* Child process */
        setsid();
        execve(cmd, argv, envp);
        /* If execve returns, it failed */
        LOGE("execve(%s) failed: %s", cmd, strerror(errno));
        _exit(127);
    } else {
        /* Parent process */
        buf[0] = masterFd;
        buf[1] = pid;
    }

    /* Cleanup JNI strings (only in parent — child has exec'd or exited) */
    (*env)->ReleaseStringUTFChars(env, jCmd, cmd);
    for (int i = 0; i < argc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, jArgs, i);
        (*env)->ReleaseStringUTFChars(env, s, argv[i]);
    }
    for (int i = 0; i < envc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, jEnvVars, i);
        (*env)->ReleaseStringUTFChars(env, s, envp[i]);
    }
    free(argv);
    free(envp);

    (*env)->SetIntArrayRegion(env, result, 0, 2, buf);
    return result;
}

/*
 * Set the terminal size on a PTY master fd.
 * Returns 0 on success, -1 on failure.
 */
JNIEXPORT jint JNICALL
Java_sh_haven_core_local_PtyBridge_nativeSetSize(
    JNIEnv *env, jclass cls,
    jint fd, jint rows, jint cols)
{
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = rows;
    ws.ws_col = cols;
    return ioctl(fd, TIOCSWINSZ, &ws);
}

/*
 * Wait for a child process to exit. Returns exit status, or -1 on error.
 */
JNIEXPORT jint JNICALL
Java_sh_haven_core_local_PtyBridge_nativeWaitPid(
    JNIEnv *env, jclass cls, jint pid)
{
    int status;
    if (waitpid(pid, &status, 0) < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}
