package com.nextscreen.app.airplay

/**
 * Callback interface invoked from native RAOP / AirPlay threads.
 */
interface AirPlayCallback {
    fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean)
    fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float)
    fun onMirrorRunning(running: Boolean)
    fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean)
    fun onAudioTeardown()
    fun onClientVolume(db: Float)
    fun onClientVolumeQuery(): Float
    fun onConnectionInit()
    fun onConnectionDestroy()
    fun onConnectionReset(reason: Int)
    fun onDisplayPin(pin: String)
    fun onVideoPlay(location: String, startPosition: Float)
    fun onVideoScrub(position: Float)
    fun onVideoRate(rate: Float)
    fun onVideoStop()
    fun onVideoSessionPoll()
    fun onLog(msg: String)
}

/**
 * JNI entry points into libnextscreen_airplay.so (UxPlay RAOP + Oboe/AMediaCodec audio engine).
 */
object AirPlayBridge {
    const val FT_MIRROR = 7
    const val FT_AUDIO = 9

    init {
        System.loadLibrary("nextscreen_airplay")
    }

    external fun nativeInit(
        callback: AirPlayCallback,
        hwAddr: ByteArray,
        name: String,
        keyFile: String,
        nohold: Boolean,
        requirePin: Boolean,
    ): Long

    external fun nativeStart(handle: Long, requestedPort: Int): Int
    external fun nativeStop(handle: Long)
    external fun nativeDestroy(handle: Long)
    external fun nativeDisconnectClients(handle: Long)

    external fun nativeSetDisplaySize(handle: Long, w: Int, h: Int, fps: Int)
    external fun nativeSetPlist(handle: Long, key: String, value: Int)
    external fun nativeSetH265Enabled(handle: Long, enabled: Boolean)
    external fun nativeSetHlsEnabled(handle: Long, enabled: Boolean)
    external fun nativeSetLang(handle: Long, lang: String)
    external fun nativeSetFeature(handle: Long, bit: Int, enabled: Boolean)
    external fun nativeSetCodecs(handle: Long, alac: Boolean, aac: Boolean)
    external fun nativeUpdatePlaybackInfo(
        handle: Long,
        position: Float,
        duration: Float,
        rate: Float,
        readyToPlay: Boolean,
    )

    external fun nativeGetRaopTxtRecords(handle: Long): Map<String, String>?
    external fun nativeGetAirplayTxtRecords(handle: Long): Map<String, String>?
    external fun nativeGetRaopServiceName(handle: Long): String?
    external fun nativeGetServerName(handle: Long): String?

    external fun nativeSetDefaultStreamValues(sampleRate: Int, framesPerBurst: Int)
    external fun nativeAudioConfigure(
        handle: Long,
        cushionMs: Int,
        percentilePct: Int,
        oboeBufferFrames: Int,
        realtimePriority: Boolean,
        lowLatency: Boolean,
    ): Boolean

    external fun nativeAudioStart(handle: Long): Boolean
    external fun nativeAudioStop(handle: Long)
    external fun nativeAudioSetClientGain(handle: Long, gain: Float)
    external fun nativeAudioFormat(handle: Long, ct: Int, spf: Int)
}
