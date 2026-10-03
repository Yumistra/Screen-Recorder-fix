package com.haseeb.recorder.shizuku

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import rikka.shizuku.Shizuku
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

private const val DESCRIPTOR = "com.haseeb.recorder.shizuku.ShizukuAudioService"
private const val TRANSACTION_START = 1
private const val TRANSACTION_READ = 2
private const val TRANSACTION_STOP = 3
private const val TRANSACTION_DESTROY = 16777115

/*
 * Runs inside the Shizuku user-service process (shell uid), NOT inside the app.
 *
 * Experimental "dual output" capture:
 *  1. REMOTE_SUBMIX is opened as the shell identity, which makes Android send media into it.
 *  2. The media strategy is then told to prefer BOTH the real output device and the submix,
 *     so the system mixer itself feeds the speaker and the recording at the same time.
 * Nothing is played back by this app, so there is no replay delay.
 * If the dual route cannot be confirmed, start fails and the app falls back to normal capture.
 */
class ShizukuAudioService : Binder {

    private val baseContext: Context?
    private var record: AudioRecord? = null
    private var manager: AudioManager? = null
    private val applied = ArrayList<Any>()
    private var info: String? = null
    private var stereoBuf = ByteArray(0)

    constructor() : super() {
        baseContext = null
        attachInterface(null, DESCRIPTOR)
    }

    constructor(context: Context) : super() {
        baseContext = context
        attachInterface(null, DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TRANSACTION_START -> {
                data.enforceInterface(DESCRIPTOR)
                val error = startCapture(data.readInt())
                reply?.writeNoException()
                reply?.writeString(error)
                reply?.writeString(info)
                return true
            }
            TRANSACTION_READ -> {
                data.enforceInterface(DESCRIPTOR)
                val frames = data.readInt().coerceIn(1, 8192)
                val bytes = readStereo(frames)
                reply?.writeNoException()
                reply?.writeInt(bytes)
                if (bytes > 0) reply?.writeByteArray(stereoBuf, 0, bytes)
                return true
            }
            TRANSACTION_STOP -> {
                data.enforceInterface(DESCRIPTOR)
                stopCapture()
                reply?.writeNoException()
                return true
            }
            TRANSACTION_DESTROY -> {
                stopCapture()
                exitProcess(0)
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    /*
     * Sets up the dual route and the capture. Returns null on success or an error text.
     */
    @Synchronized
    @SuppressLint("MissingPermission")
    private fun startCapture(sampleRate: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "Android 13+ required"
        stopCapture()
        info = null
        var stage = "init"
        try {
            val base = baseContext ?: systemContext() ?: return "no context in service process"

            stage = "audio manager"
            val am = obtainAudioManager(base) ?: return "no AudioManager"
            manager = am
            val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()

            stage = "strategies"
            val strategies = findStrategies()
            if (strategies.isEmpty()) return "no media strategy found"
            // A previous session that died without cleanup may have left its preference behind.
            for (s in strategies) removePreferred(am, s)

            stage = "current devices"
            val real = devicesFor(am, media).filter { !isSubmix(it) }
            if (real.isEmpty()) return "no output device"
            val unsupported = real.firstOrNull { typeOf(it) !in DUAL_OK_TYPES }
            if (unsupported != null) return "output device type ${typeOf(unsupported)} is not supported for dual output"

            stage = "open submix"
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val builder = AudioRecord.Builder()
                .setContext(ShellContext(base))
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(format)
            if (minBuf > 0) builder.setBufferSizeInBytes(minBuf * 8)
            val rec = builder.build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return "AudioRecord state=${rec.state}"
            }
            record = rec
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                stopCapture()
                return "AudioRecord did not start (uid=${Process.myUid()})"
            }

            stage = "find submix device"
            var submix: Any? = null
            val submixDeadline = SystemClock.elapsedRealtime() + 1000
            while (submix == null && SystemClock.elapsedRealtime() < submixDeadline) {
                submix = devicesFor(am, media).firstOrNull { isSubmix(it) }
                if (submix == null) SystemClock.sleep(40)
            }
            if (submix == null) submix = newSubmixAttributes()
            if (submix == null) {
                stopCapture()
                return "submix device not found"
            }

            stage = "set preferred devices"
            val target = ArrayList<Any>(real)
            target.add(submix)
            for (s in strategies) {
                applied.add(s)
                if (!setPreferred(am, s, target)) {
                    stopCapture()
                    return "setPreferredDevicesForStrategy was rejected"
                }
            }

            stage = "verify"
            var current: List<Any> = emptyList()
            var ok = false
            val verifyDeadline = SystemClock.elapsedRealtime() + 1000
            while (!ok && SystemClock.elapsedRealtime() < verifyDeadline) {
                current = devicesFor(am, media)
                ok = current.any { isSubmix(it) } && current.any { !isSubmix(it) }
                if (!ok) SystemClock.sleep(50)
            }
            if (!ok) {
                val seen = describe(current)
                stopCapture()
                return "dual route not applied (devices=$seen)"
            }
            info = "devices=${describe(current)}"
            return null
        } catch (e: Throwable) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            stopCapture()
            return "$stage: ${cause.javaClass.simpleName}: ${cause.message} (uid=${Process.myUid()})"
        }
    }

    /*
     * Blocking read of [frames] stereo frames. Returns the byte count or a negative error.
     */
    private fun readStereo(frames: Int): Int {
        val rec = record ?: return -1
        if (stereoBuf.size < frames * 4) stereoBuf = ByteArray(frames * 4)
        val read = rec.read(stereoBuf, 0, frames * 4)
        return if (read > 0) read - (read % 4) else read
    }

    /*
     * Restores normal routing first, then closes the capture.
     */
    @Synchronized
    private fun stopCapture() {
        val am = manager
        if (am != null) {
            for (s in applied) removePreferred(am, s)
        }
        applied.clear()
        val rec = record
        record = null
        if (rec != null) {
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
        }
    }

    private fun obtainAudioManager(base: Context): AudioManager? {
        try {
            (base.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.let { return it }
        } catch (_: Throwable) {
        }
        return try {
            AudioManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(base)
        } catch (_: Throwable) {
            null
        }
    }

    /*
     * Product strategies that carry media and game audio.
     */
    private fun findStrategies(): List<Any> {
        val all = AudioManager::class.java.getMethod("getAudioProductStrategies").invoke(null) as? List<*> ?: return emptyList()
        val cls = Class.forName(STRATEGY_CLASS)
        val supports = cls.getMethod("supportsAudioAttributes", AudioAttributes::class.java)
        val getId = cls.getMethod("getId")
        val usages = intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME)
        val seen = HashSet<Int>()
        val result = ArrayList<Any>()
        for (strategy in all) {
            if (strategy == null) continue
            for (usage in usages) {
                val attrs = AudioAttributes.Builder().setUsage(usage).build()
                if (supports.invoke(strategy, attrs) == true) {
                    if (seen.add(getId.invoke(strategy) as Int)) result.add(strategy)
                    break
                }
            }
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun devicesFor(am: AudioManager, attrs: AudioAttributes): List<Any> {
        val method = AudioManager::class.java.getMethod("getDevicesForAttributes", AudioAttributes::class.java)
        return (method.invoke(am, attrs) as? List<Any>) ?: emptyList()
    }

    private fun typeOf(device: Any): Int {
        return device.javaClass.getMethod("getType").invoke(device) as Int
    }

    private fun isSubmix(device: Any): Boolean {
        val internal = try {
            device.javaClass.getMethod("getInternalType").invoke(device) as? Int
        } catch (_: Throwable) {
            null
        }
        return internal == 0x8000 || typeOf(device) == AudioDeviceInfo.TYPE_REMOTE_SUBMIX
    }

    private fun describe(devices: List<Any>): String {
        return devices.joinToString(",") { typeOf(it).toString() }
    }

    private fun setPreferred(am: AudioManager, strategy: Any, devices: List<Any>): Boolean {
        val method = AudioManager::class.java.getMethod("setPreferredDevicesForStrategy", Class.forName(STRATEGY_CLASS), List::class.java)
        return method.invoke(am, strategy, devices) == true
    }

    private fun removePreferred(am: AudioManager, strategy: Any) {
        try {
            AudioManager::class.java.getMethod("removePreferredDeviceForStrategy", Class.forName(STRATEGY_CLASS)).invoke(am, strategy)
        } catch (_: Throwable) {
        }
    }

    /*
     * Fallback when the system does not report the submix device itself (0x8000 = DEVICE_OUT_REMOTE_SUBMIX).
     */
    private fun newSubmixAttributes(): Any? {
        return try {
            Class.forName("android.media.AudioDeviceAttributes")
                .getConstructor(Int::class.javaPrimitiveType, String::class.java)
                .newInstance(0x8000, "0")
        } catch (_: Throwable) {
            null
        }
    }

    /*
     * Fallback for Shizuku versions that do not pass a Context to the service constructor.
     */
    private fun systemContext(): Context? {
        return try {
            val cls = Class.forName("android.app.ActivityThread")
            val thread = cls.getMethod("currentActivityThread").invoke(null)
                ?: cls.getMethod("systemMain").invoke(null)
            cls.getMethod("getSystemContext").invoke(thread) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    /*
     * Makes AudioRecord attribute the capture to the identity this process really runs as.
     */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        private val pkg = if (Process.myUid() == 0) "root" else "com.android.shell"
        override fun getPackageName(): String = pkg
        override fun getOpPackageName(): String = pkg
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(pkg).build()
    }

    private companion object {
        const val STRATEGY_CLASS = "android.media.audiopolicy.AudioProductStrategy"

        // Devices that share the primary output, which is what the system can duplicate with the submix.
        val DUAL_OK_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        )
    }
}

/*
 * App-side handle for ShizukuAudioService.
 * bind() and startAsync() are called on the main thread; read() is called from the audio thread.
 */
class ShizukuAudioClient(context: Context) {

    companion object {
        private const val TAG = "ShizukuAudioClient"
        const val STATE_STARTING = 0
        const val STATE_READY = 1
        const val STATE_FAILED = 2
    }

    @Volatile private var remote: IBinder? = null
    @Volatile private var released = false
    private var bound = false

    @Volatile var state = STATE_STARTING
        private set

    // Error text when FAILED, routing info when READY.
    @Volatile var message: String? = null
        private set

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuAudioService::class.java.name)
    ).daemon(false).processNameSuffix("audio").debuggable(false).version(3)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
        }
    }

    fun bind(): Boolean {
        return try {
            Shizuku.bindUserService(args, connection)
            bound = true
            true
        } catch (e: Throwable) {
            Log.e(TAG, "bindUserService failed: ${e.message}")
            false
        }
    }

    /*
     * Waits for the service and sets up the capture without blocking the caller.
     */
    fun startAsync(sampleRate: Int) {
        Thread({
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (remote == null && !released && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            if (released) return@Thread
            val binder = remote
            if (binder == null) {
                message = "Shizuku 서비스 연결 시간 초과"
                state = STATE_FAILED
                return@Thread
            }
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeInt(sampleRate)
                binder.transact(TRANSACTION_START, data, reply, 0)
                reply.readException()
                val error = reply.readString()
                val info = reply.readString()
                message = error ?: info
                state = if (error == null) STATE_READY else STATE_FAILED
            } catch (e: Throwable) {
                message = "${e.javaClass.simpleName}: ${e.message}"
                state = STATE_FAILED
            } finally {
                data.recycle()
                reply.recycle()
            }
        }, "ShizukuAudioStart").start()
    }

    /*
     * Blocking read of up to [frames] interleaved stereo frames into [out] (needs frames * 2 shorts).
     * Returns the frame count or a negative error.
     */
    fun read(out: ShortArray, frames: Int): Int {
        val binder = remote
        if (binder == null || state != STATE_READY) return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(frames)
            binder.transact(TRANSACTION_READ, data, reply, 0)
            reply.readException()
            val bytes = reply.readInt()
            if (bytes <= 0) {
                bytes
            } else {
                val buf = reply.createByteArray() ?: return -1
                val count = minOf(buf.size / 4, frames, out.size / 2)
                ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out, 0, count * 2)
                count
            }
        } catch (e: Throwable) {
            Log.e(TAG, "read failed: ${e.message}")
            -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /*
     * Restores normal routing and removes the service process.
     */
    fun release() {
        released = true
        val binder = remote
        if (binder != null) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                binder.transact(TRANSACTION_STOP, data, reply, 0)
            } catch (_: Throwable) {
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
        remote = null
        if (bound) {
            bound = false
            try { Shizuku.unbindUserService(args, connection, true) } catch (_: Throwable) {}
        }
    }
}
