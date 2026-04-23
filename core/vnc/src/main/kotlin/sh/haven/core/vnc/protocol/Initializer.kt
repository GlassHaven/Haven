package sh.haven.core.vnc.protocol

import sh.haven.core.vnc.VncSession

/**
 * Performs VNC initialization after handshake: sends ClientInit, receives
 * ServerInit, configures pixel format and encodings.
 */
object Initializer {

    fun initialise(session: VncSession) {
        ClientInit(session.config.shared).encode(session.outputStream)

        val serverInit = ServerInit.decode(session.inputStream)
        session.serverInit = serverInit
        session.framebufferWidth = serverInit.framebufferWidth
        session.framebufferHeight = serverInit.framebufferHeight

        val depth = session.config.colorDepth
        val pixelFormat = PixelFormat(
            bitsPerPixel = depth.bitsPerPixel,
            depth = depth.depth,
            bigEndian = false,
            trueColor = depth.trueColor,
            redMax = depth.redMax,
            greenMax = depth.greenMax,
            blueMax = depth.blueMax,
            redShift = depth.redShift,
            greenShift = depth.greenShift,
            blueShift = depth.blueShift,
        )
        session.pixelFormat = pixelFormat
        SetPixelFormat(pixelFormat).encode(session.outputStream)

        // Preference order matters: servers pick the first advertised encoding
        // they support. ZRLE is the modern default on TigerVNC / RealVNC, ZLIB
        // is a useful fallback for older servers that lack it.
        val encodings = mutableListOf<Encoding>()
        encodings += Encoding.ZRLE
        encodings += Encoding.HEXTILE
        encodings += Encoding.ZLIB
        encodings += Encoding.RRE
        encodings += Encoding.COPYRECT
        encodings += Encoding.RAW
        encodings += Encoding.DESKTOP_SIZE
        encodings += Encoding.CURSOR
        SetEncodings(encodings).encode(session.outputStream)
        session.outputStream.flush()
    }
}
