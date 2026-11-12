package sh.haven.core.local;

import android.os.ParcelFileDescriptor;

interface IShizukuPtyService {
    void destroy() = 16777114;

    ParcelFileDescriptor openPtySession(
        String sessionId,
        String cmd,
        in String[] args,
        in String[] env,
        int rows,
        int cols
    ) = 1;

    void startPipeSession(
        String sessionId,
        String cmd,
        in String[] args,
        in String[] env
    ) = 2;

    ParcelFileDescriptor takeSessionFd(String sessionId, int stream) = 3;

    int resizeSession(String sessionId, int rows, int cols) = 4;

    int waitExit(String sessionId) = 5;

    void closeSession(String sessionId) = 6;
}
