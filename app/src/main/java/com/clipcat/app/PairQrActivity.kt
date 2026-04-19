package com.clipcat.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.clipcat.app.databinding.ActivityPairQrBinding
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class PairQrActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPairQrBinding
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var scanner: BarcodeScanner? = null
    private val lastInvalidQrToastMs = AtomicLong(0)
    @Volatile
    private var handled = false
    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(this, "Camera permission is required to scan QR", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPairQrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!handled && ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanner()
        }
    }

    override fun onPause() {
        super.onPause()
        cameraProvider?.unbindAll()
    }

    private fun startScanner() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.scannerPreview.surfaceProvider)
            }

            val scannerOptions = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            scanner = BarcodeScanning.getClient(scannerOptions)

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null || handled) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val activeScanner = scanner
                if (activeScanner == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                activeScanner.process(inputImage)
                    .addOnSuccessListener { results ->
                        if (handled) return@addOnSuccessListener
                        val qr = results.firstOrNull()?.rawValue ?: return@addOnSuccessListener
                        try {
                            val json = JSONObject(qr)
                            val ip = getStringAny(json, "ip", "Ip")
                            val port = getIntAny(json, "port", "Port")
                            val key = getStringAny(json, "key", "Key")

                            handled = true
                            cameraProvider?.unbindAll()

                            setResult(
                                RESULT_OK,
                                Intent()
                                    .putExtra(EXTRA_IP, ip)
                                    .putExtra(EXTRA_PORT, port)
                                    .putExtra(EXTRA_KEY, key)
                            )
                            finish()
                        } catch (_: Exception) {
                            showInvalidQrMessageOnce()
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            }

            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getStringAny(json: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (json.has(key)) {
                return json.getString(key)
            }
        }
        throw IllegalArgumentException("Missing required key: ${keys.joinToString()}")
    }

    private fun getIntAny(json: JSONObject, vararg keys: String): Int {
        for (key in keys) {
            if (json.has(key)) {
                return json.getInt(key)
            }
        }
        throw IllegalArgumentException("Missing required key: ${keys.joinToString()}")
    }

    private fun showInvalidQrMessageOnce() {
        val now = System.currentTimeMillis()
        val previous = lastInvalidQrToastMs.get()
        if (now - previous < 2000) {
            return
        }

        if (lastInvalidQrToastMs.compareAndSet(previous, now)) {
            runOnUiThread {
                Toast.makeText(this, "This QR code is not a Clipcat pairing code", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        scanner?.close()
        cameraExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IP = "extra_ip"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_KEY = "extra_key"
    }
}
