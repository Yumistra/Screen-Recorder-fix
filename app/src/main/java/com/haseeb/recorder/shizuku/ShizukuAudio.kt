package com.haseeb.recorder.shizuku

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import rikka.shizuku.Shizuku
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
 * Captures the whole device audio output through REMOTE_SUBMIX, which the shell
 * identity is allowed to open, and hands stereo 16-bit PCM back over Binder.
 * While this capture is active Android routes media playback to the submix,
 * so the device itself stays silent unless the app plays the audio back.
 */
class ShizukuAudioService : Binder {

    private val baseContext: Context?
    private var record: AudioRecord? = null
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
     * Opens REMOTE_SUBMIX as the shell identity. Returns null on success or an error text.
     */
    @SuppressLint("MissingPermission")
    private fun startCapture(sampleRate: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "Android 12+ required"
        return try {
            stopCapture()
            val base = baseContext ?: systemContext() ?: return "no context in service process"
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
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                return "AudioRecord did not start (uid=${Process.myUid()})"
            }
            record = rec
            null
        } catch (e: Throwable) {
            "${e.javaClass.simpleName}: ${e.message} (uid=${Process.myUid()})"
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

    private fun stopCapture() {
        val rec = record ?: return
        record = null
        try { rec.stop() } catch (_: Throwable) {}
        try { rec.release() } catch (_: Throwable) {}
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
}

/*
 * App-side handle for ShizukuAudioService.
 * bind() must be called on the main thread; start()/read() are called from the audio thread.
 */
class ShizukuAudioClient(context: Context) {

    companion object {
        private const val TAG = "ShizukuAudioClient"
    }

    @Volatile private var remote: IBinder? = null
    @Volatile private var started = false
    private var bound = false

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuAudioService::class.java.name)
    ).daemon(false).processNameSuffix("audio").debuggable(false).version(2)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            started = false
        }
    }

    val isConnected: Boolean
        get() = remote != null

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
     * Starts the capture in the service process. Returns null on success or an error text.
     */
    fun start(sampleRate: Int): String? {
        val binder = remote ?: return "service not connected"
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(sampleRate)
            binder.transact(TRANSACTION_START, data, reply, 0)
            reply.readException()
            val error = reply.readString()
            started = error == null
            error
        } catch (e: Throwable) {
            "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /*
     * Blocking read of up to [frames] interleaved stereo frames into [out] (needs frames * 2 shorts).
     * Returns the frame count or a negative error.
     */
    fun read(out: ShortArray, frames: Int): Int {
        val binder = remote
        if (binder == null || !started) return -1
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
     * Stops the capture and removes the service process, which gives the device its sound back.
     */
    fun release() {
        val binder = remote
        if (binder != null && started) {
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
        started = false
        remote = null
        if (bound) {
            bound = false
            try { Shizuku.unbindUserService(args, connection, true) } catch (_: Throwable) {}
        }
    }
}
