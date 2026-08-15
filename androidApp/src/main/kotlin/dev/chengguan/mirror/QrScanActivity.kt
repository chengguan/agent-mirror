package dev.chengguan.mirror

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
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
        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener { cancelScan() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = 64 }
        }
        root.addView(preview)
        root.addView(hint)
        root.addView(cancel)
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

    override fun scan(onResult: (payload: String?, error: String?) -> Unit) {
        pending = onResult
        launcher.launch(Intent(activity, QrScanActivity::class.java))
    }
}
