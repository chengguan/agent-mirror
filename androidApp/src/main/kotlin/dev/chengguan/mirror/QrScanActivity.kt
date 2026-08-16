package dev.chengguan.mirror

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX + bundled ML Kit QR reader. The payload (contains tok) is returned
 * to the caller and never logged. Frames are not stored.
 */
class QrScanActivity : FragmentActivity() {
    private val finished = AtomicBoolean(false)
    private val hint = TextView(this)
    private var cameraProvider: ProcessCameraProvider? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else fail(NO_PERMISSION)
    }

    private val albumLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        decodePairingQrFromUri(this, uri) { payload, error ->
            if (payload != null) {
                onPayload(payload)
            } else {
                hint.text = error ?: "No QR code in that photo."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // OWASP M6: pairing QR contains tok — keep it off screenshots.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        window.statusBarColor = BG

        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val preview = PreviewView(this).apply {
            id = PREVIEW_ID
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        hint.apply {
            text = "Point at the pairing QR"
            setTextColor(INK)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            )
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = 64 }
        }
        val photos = Button(this).apply {
            text = "Choose from Photos"
            setOnClickListener { albumLauncher.launch(pickImageRequest()) }
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener { cancelScan() }
        }
        actions.addView(photos)
        actions.addView(cancel)
        root.addView(preview)
        root.addView(hint)
        root.addView(actions)
        setContentView(root)

        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        super.onDestroy()
    }

    private fun startCamera() {
        val previewView = findViewById<PreviewView>(PREVIEW_ID)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                if (finished.get()) return@addListener
                val provider = runCatching { future.get() }.getOrNull()
                if (provider == null) {
                    fail("Could not start the camera.")
                    return@addListener
                }
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
                    .build()
                    .also { it.setAnalyzer(ContextCompat.getMainExecutor(this), QrAnalyzer(::onPayload)) }
                val selector = when {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                        CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> {
                        fail("This device has no camera. Paste the pairing URL instead.")
                        return@addListener
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, preview, analysis)
                }.onFailure {
                    fail("Could not start the camera.")
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun onPayload(value: String) {
        if (!looksLikePairingPayload(value)) {
            hint.text = "Not a Mirror pairing QR"
            return
        }
        if (!finished.compareAndSet(false, true)) return
        cameraProvider?.unbindAll()
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PAYLOAD, value))
        finish()
    }

    private fun fail(message: String) {
        if (!finished.compareAndSet(false, true)) return
        cameraProvider?.unbindAll()
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
        finish()
    }

    private fun cancelScan() {
        if (!finished.compareAndSet(false, true)) return
        cameraProvider?.unbindAll()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_ERROR = "error"
        private const val NO_PERMISSION =
            "Camera access is off. Enable it in Settings to scan the QR."
        private const val PREVIEW_ID = 0x51
        private const val BG = Color.BLACK
        private val INK = Color.rgb(0xE1, 0xE1, 0xE1)
    }
}

private class QrAnalyzer(
    private val onPayload: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val media = imageProxy.image
        if (media == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstNotNullOfOrNull { it.rawValue }?.trim().orEmpty()
                if (value.isNotEmpty() && value.length <= 2_000) {
                    onPayload(value)
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}

class AndroidQrScanHost(private val activity: FragmentActivity) : QrScanHost {
    private var pending: ((String?, String?) -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val cb = pending
        pending = null
        val payload = result.data?.getStringExtra(QrScanActivity.EXTRA_PAYLOAD)
        val error = result.data?.getStringExtra(QrScanActivity.EXTRA_ERROR)
        when {
            result.resultCode == Activity.RESULT_OK && !payload.isNullOrEmpty() -> cb?.invoke(payload, null)
            !error.isNullOrEmpty() -> cb?.invoke(null, error)
            else -> cb?.invoke(null, null)
        }
    }

    private val albumLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val cb = pending
        pending = null
        if (uri == null) {
            cb?.invoke(null, null)
            return@registerForActivityResult
        }
        decodePairingQrFromUri(activity, uri) { payload, error ->
            cb?.invoke(payload, error)
        }
    }

    override fun scan(onResult: (payload: String?, error: String?) -> Unit) {
        pending = onResult
        launcher.launch(Intent(activity, QrScanActivity::class.java))
    }

    override fun pickFromAlbum(onResult: (payload: String?, error: String?) -> Unit) {
        pending = onResult
        albumLauncher.launch(pickImageRequest())
    }
}

private fun pickImageRequest() =
    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

internal fun decodePairingQrFromUri(
    context: Context,
    uri: Uri,
    onDone: (String?, String?) -> Unit,
) {
    val bitmap = runCatching { loadDownsampledBitmap(context, uri) }.getOrNull()
    if (bitmap == null) {
        onDone(null, "Could not read that photo.")
        return
    }
    val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )
    scanner.process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener { barcodes ->
            val values = barcodes.mapNotNull { it.rawValue?.trim()?.takeIf(String::isNotEmpty) }
                .filter { it.length <= 2_000 }
            val match = values.firstOrNull(::looksLikePairingPayload)
            when {
                match != null -> onDone(match, null)
                values.isEmpty() -> onDone(null, "No QR code in that photo.")
                else -> onDone(null, "That photo is not a Mirror pairing QR.")
            }
        }
        .addOnFailureListener {
            onDone(null, "Could not read a QR code in that photo.")
        }
        .addOnCompleteListener { scanner.close() }
}

private fun loadDownsampledBitmap(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longest / sample > 1_600) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    }
}
