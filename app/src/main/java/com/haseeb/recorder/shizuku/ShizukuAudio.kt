package com.haseeb.recorder.shizuku

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.media.AudioAttributes
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
private const val TRANSACTION_DIAG = 4
private const val TRANSACTION_DESTROY = 16777115

/*
 * Runs inside the Shizuku user-service process (shell uid), NOT inside the app.
 *
 * Same technique as scrcpy's "playback" audio source with audio duplication:
 * an audio policy mix with ROUTE_FLAG_LOOP_BACK_RENDER is registered as the shell identity.
 * Android keeps playing the sound on the device exactly as before and hands over a copy,
 * so nothing is intercepted or replayed and there is no added delay.
 *
 * Compared to the normal MediaProjection capture the shell may also capture
 * USAGE_VOICE_COMMUNICATION, which is what games and apps in "call" audio mode use.
 * Apps that explicitly forbid capture are still not recorded.
 */
class ShizukuAudioService : Binder {

    private val baseContext: Context?
    private var record: AudioRecord? = null
    private var policy: Any? = null
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
            TRANSACTION_DIAG -> {
                data.enforceInterface(DESCRIPTOR)
                val text = describePlayers()
                reply?.writeNoException()
                reply?.writeString(text)
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
     * Registers the capture policy and opens its record. Returns null on success or an error text.
     * Voice-communication capture needs an extra shell permission; if the system refuses it,
     * the policy is registered again without it.
     */
    @Synchronized
    private fun startCapture(sampleRate: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "Android 13+ required"
        stopCapture()
        info = null
        val base = baseContext ?: systemContext() ?: return "no context in service process"
        val shell = ShellContext(base)

        val withVoice = tryStart(shell, sampleRate, true)
        if (withVoice == null) {
            info = "media+game+call"
            return null
        }
        val mediaOnly = tryStart(shell, sampleRate, false)
        if (mediaOnly == null) {
            info = "media+game only (call audio refused: $withVoice)"
            return null
        }
        return mediaOnly
    }

    @SuppressLint("MissingPermission")
    private fun tryStart(shell: Context, sampleRate: Int, voiceCommunication: Boolean): String? {
        var stage = "mixing rule"
        try {
            val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderCls = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val ruleBuilder = ruleBuilderCls.getConstructor().newInstance()
            ruleBuilderCls.getMethod("setTargetMixRole", Int::class.javaPrimitiveType)
                .invoke(ruleBuilder, ruleCls.getField("MIX_ROLE_PLAYERS").getInt(null))
            val matchUsage = ruleCls.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
            val addMixRule = ruleBuilderCls.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
            val usages = ArrayList<Int>()
            usages.add(AudioAttributes.USAGE_MEDIA)
            usages.add(AudioAttributes.USAGE_GAME)
            usages.add(AudioAttributes.USAGE_UNKNOWN)
            if (voiceCommunication) usages.add(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            for (usage in usages) {
                addMixRule.invoke(ruleBuilder, matchUsage, AudioAttributes.Builder().setUsage(usage).build())
            }
            if (voiceCommunication) {
                ruleBuilderCls.getMethod("voiceCommunicationCaptureAllowed", Boolean::class.javaPrimitiveType)
                    .invoke(ruleBuilder, true)
            }
            val rule = ruleBuilderCls.getMethod("build").invoke(ruleBuilder)

            stage = "mix"
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilderCls = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mixBuilder = mixBuilderCls.getConstructor(ruleCls).newInstance(rule)
            mixBuilderCls.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            mixBuilderCls.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
                .invoke(mixBuilder, mixCls.getField("ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null))
            val mix = mixBuilderCls.getMethod("build").invoke(mixBuilder)

            stage = "policy"
            val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            val policyBuilderCls = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val policyBuilder = policyBuilderCls.getConstructor(Context::class.java).newInstance(shell)
            policyBuilderCls.getMethod("addMix", mixCls).invoke(policyBuilder, mix)
            val newPolicy = policyBuilderCls.getMethod("build").invoke(policyBuilder)

            stage = "register"
            val register = AudioManager::class.java.getDeclaredMethod("registerAudioPolicyStatic", policyCls)
            register.isAccessible = true
            val result = register.invoke(null, newPolicy) as Int
            if (result != 0) return "registerAudioPolicy returned $result"
            policy = newPolicy

            stage = "record"
            val rec = buildRecord(shell, mixCls, mix, format, sampleRate)
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                stopCapture()
                return "AudioRecord state=${rec.state}"
            }
            record = rec
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                stopCapture()
                return "AudioRecord did not start"
            }
            return null
        } catch (e: Throwable) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            stopCapture()
            return "$stage: ${cause.javaClass.simpleName}: ${cause.message}"
        }
    }

    /*
     * Equivalent of AudioPolicy.createAudioRecordSink(), but attributed to the shell identity
     * through an explicit Context (the service process has no application of its own).
     */
    @SuppressLint("MissingPermission")
    private fun buildRecord(shell: Context, mixCls: Class<*>, mix: Any?, format: AudioFormat, sampleRate: Int): AudioRecord {
        val address = mixCls.getMethod("getRegistration").invoke(mix) as String
        val attrsBuilder = AudioAttributes.Builder()
        val attrsBuilderCls = AudioAttributes.Builder::class.java
        attrsBuilderCls.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            .invoke(attrsBuilder, MediaRecorder.AudioSource.REMOTE_SUBMIX)
        val addTag = attrsBuilderCls.getMethod("addTag", String::class.java)
        addTag.invoke(attrsBuilder, "addr=$address")
        addTag.invoke(attrsBuilder, "fixedVolume")

        val builder = AudioRecord.Builder().setContext(shell).setAudioFormat(format)
        AudioRecord.Builder::class.java.getMethod("setAudioAttributes", AudioAttributes::class.java)
            .invoke(builder, attrsBuilder.build())
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf > 0) builder.setBufferSizeInBytes(minBuf * 8)
        return builder.build()
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

    @Synchronized
    private fun stopCapture() {
        val rec = record
        record = null
        if (rec != null) {
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
        }
        val oldPolicy = policy
        policy = null
        if (oldPolicy != null) {
            try {
                val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
                val unregister = AudioManager::class.java.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyCls)
                unregister.isAccessible = true
                unregister.invoke(null, oldPolicy)
            } catch (_: Throwable) {
                // The system also drops the policy when this process exits.
            }
        }
    }

    /*
     * Lists what is playing right now and how each player is tagged, so a silent
     * recording can be explained: usage tells the audio mode, flags tell whether capture is forbidden.
     */
    private fun describePlayers(): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "audio"))
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val out = StringBuilder()
            for (line in text.lineSequence()) {
                if (!line.contains("AudioPlaybackConfiguration") || !line.contains("state:started")) continue
                val uid = Regex("u/pid:(\\d+)/").find(line)?.groupValues?.get(1)
                val usage = Regex("usage=(\\w+)").find(line)?.groupValues?.get(1)
                val flags = Regex("flags=(0x[0-9a-fA-F]+)").find(line)?.groupValues?.get(1)
                val name = uid?.toIntOrNull()?.let { id ->
                    try { baseContext?.packageManager?.getNameForUid(id) } catch (_: Throwable) { null }
                }
                out.append(name ?: "uid $uid").append(' ').append(usage).append(" flags=").append(flags).append('\n')
            }
            if (out.isEmpty()) "no active players" else out.toString().trim()
        } catch (e: Throwable) {
            "diag failed: ${e.javaClass.simpleName}: ${e.message}"
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
     * Makes the audio framework attribute requests to the identity this process really runs as.
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

    // Error text when FAILED, capture scope when READY.
    @Volatile var message: String? = null
        private set

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuAudioService::class.java.name)
    ).daemon(false).processNameSuffix("audio").debuggable(false).version(4)

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
                message = "Shizuku service connection timed out"
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
     * Asks the service what is currently playing and how it is tagged. Blocking; call off the main thread.
     */
    fun describePlayers(): String? {
        val binder = remote ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(TRANSACTION_DIAG, data, reply, 0)
            reply.readException()
            reply.readString()
        } catch (e: Throwable) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /*
     * Stops the capture and removes the service process.
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
