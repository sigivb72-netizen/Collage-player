package com.collageplayer.collage

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build

/**
 * Asks the phone how many hardware video decoders it can run at the same time.
 * Collage mode uses this to warn before the device is overloaded.
 */
object DecoderBudget {

    fun maxHardwareInstances(mime: String = "video/avc"): Int {
        return try {
            var best = 0
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(mime, true) }) continue
                val hw = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                if (!hw) continue
                val n = info.getCapabilitiesForType(mime).maxSupportedInstances
                if (n > best) best = n
            }
            best
        } catch (_: Throwable) {
            0
        }
    }

    fun isLowRam(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
}
