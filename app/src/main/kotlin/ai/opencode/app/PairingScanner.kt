package ai.opencode.app

import ai.opencode.core.log.AppLog
import android.content.Context
import android.content.pm.PackageManager
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The camera scanner for the QR code `opencode pair` prints (iOS `PairingScannerView`'s
 * capture session). Google's code scanner reads the one symbology needed and draws its own
 * camera UI, with no CAMERA permission. Never logs the scanned text (it holds a pairing code).
 */
object PairingScanner {
    sealed interface Result {
        data class Scanned(val text: String) : Result
        data object Cancelled : Result
        /** No camera, or the scanner module can't run (iOS “No Camera Available”). */
        data object Unavailable : Result
    }

    private val UNAVAILABLE_CODES = setOf(
        MlKitException.UNAVAILABLE,
        MlKitException.CODE_SCANNER_UNAVAILABLE,
        MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED,
        MlKitException.CODE_SCANNER_APP_NAME_UNAVAILABLE,
        MlKitException.CODE_SCANNER_PIPELINE_INITIALIZATION_ERROR,
        MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD,
    )

    suspend fun scan(context: Context): Result {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return Result.Unavailable
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = GmsBarcodeScanning.getClient(context, options)
        return suspendCancellableCoroutine { continuation ->
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val text = barcode.rawValue
                    continuation.resume(if (text.isNullOrEmpty()) Result.Cancelled else Result.Scanned(text))
                }
                .addOnCanceledListener { continuation.resume(Result.Cancelled) }
                .addOnFailureListener { error ->
                    val code = (error as? MlKitException)?.errorCode
                    // Only a scanner that can't run gets the fallback; anything else (backing out
                    // arrives as CANCELLED, or INTERNAL on some Play services builds) just closes.
                    val unavailable = code in UNAVAILABLE_CODES || error !is MlKitException
                    AppLog.app.info { "pairing scanner ended error=${error.javaClass.simpleName} code=$code unavailable=$unavailable" }
                    continuation.resume(if (unavailable) Result.Unavailable else Result.Cancelled)
                }
        }
    }
}
