package com.clipcat.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.clipcat.app.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.clipcat.app.model.PairingConfig
import com.clipcat.app.network.TcpImageSender
import com.clipcat.app.storage.AppSettingsStore
import com.clipcat.app.storage.PairingStore
import com.clipcat.app.storage.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileInputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var pairingStore: PairingStore
    private lateinit var appSettingsStore: AppSettingsStore
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var isUpdatingZoomSeekBar = false
    private var lastZoomState: ZoomState? = null
    private var isExactPreviewMode = false
    private var isZoomUiExpanded = false
    private var capturePreviewJob: Job? = null
    private var capturePreviewBitmap: Bitmap? = null
    private var captureSessionCounter = 0L
    private var activeCaptureSessionId = 0L
    private var previewShownAtMs = 0L
    private var sendCompletedAtMs = 0L

    private val minPreviewVisibleMs = 620L
    private val minSentVisibleMs = 420L
    private val jointExitDurationMs = 250L

    private var touchStartY = 0f
    private var touchStartExposureIndex = 0
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var isVerticalDragExposure = false
    private var isPinchZooming = false
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private val dragZoomThresholdPx by lazy { 14f * resources.displayMetrics.density }
    private val tapSlopPx by lazy { 18f * resources.displayMetrics.density }

    private val zoomUiAutoCollapse = Runnable {
        collapseZoomUiToLabel()
    }

    private val pairLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            return@registerForActivityResult
        }

        val ip = result.data?.getStringExtra(PairQrActivity.EXTRA_IP) ?: return@registerForActivityResult
        val port = result.data?.getIntExtra(PairQrActivity.EXTRA_PORT, -1) ?: -1
        val key = result.data?.getStringExtra(PairQrActivity.EXTRA_KEY) ?: return@registerForActivityResult
        if (port <= 0) return@registerForActivityResult

        val currentConfig = pairingStore.load()
        val config = PairingConfig(
            ip = ip,
            port = port,
            keyBase64 = key,
            fastTransfer = currentConfig?.fastTransfer ?: true
        )
        pairingStore.save(config)
        Toast.makeText(this, "Paired with PC", Toast.LENGTH_SHORT).show()
        // startCamera() is handled in onResume()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            binding.permissionOverlay.visibility = View.VISIBLE
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        } else {
            binding.permissionOverlay.visibility = View.GONE
        }
        // If granted, onResume() will pick it up and call startCamera() automatically
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        appSettingsStore = AppSettingsStore(this)

        lensFacing = savedInstanceState?.getInt(KEY_LENS_FACING, appSettingsStore.getLensFacing())
            ?: appSettingsStore.getLensFacing()
        isExactPreviewMode = savedInstanceState?.getBoolean(KEY_EXACT_PREVIEW_MODE)
            ?: (appSettingsStore.getPreviewMode() == "EXACT")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.grantPermissionButton.setOnClickListener {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.parse("package:" + packageName)
            startActivity(intent)
        }

        binding.previewModeButton.setOnClickListener {
            isExactPreviewMode = !isExactPreviewMode
            appSettingsStore.setPreviewMode(if (isExactPreviewMode) "EXACT" else "FULL")
            applyPreviewMode()
        }
        applyPreviewMode()

        pairingStore = PairingStore(this)

        binding.settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        binding.pairButton.setOnClickListener {
            pairLauncher.launch(Intent(this, PairQrActivity::class.java))
        }

        binding.captureButton.setOnClickListener {
            captureAndSend()
        }

        setupZoomUi()

        binding.cameraToggleButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            appSettingsStore.setLensFacing(lensFacing)
            startCamera()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        handleIntent(intent)
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val qualityLowButton = dialogView.findViewById<MaterialButton>(R.id.qualityLowButton)
        val qualityMediumButton = dialogView.findViewById<MaterialButton>(R.id.qualityMediumButton)
        val qualityHighButton = dialogView.findViewById<MaterialButton>(R.id.qualityHighButton)
        val hapticFeedbackSwitch = dialogView.findViewById<android.widget.Switch>(R.id.hapticFeedbackSwitchDialog)
        val imageFormatGroup = dialogView.findViewById<RadioGroup>(R.id.imageFormatGroup)
        val formatJpegRadio = dialogView.findViewById<android.widget.RadioButton>(R.id.formatJpegRadio)
        val formatPngRadio = dialogView.findViewById<android.widget.RadioButton>(R.id.formatPngRadio)
        
        val themeModeGroup = dialogView.findViewById<RadioGroup>(R.id.themeModeGroup)
        val themeSystemRadio = dialogView.findViewById<android.widget.RadioButton>(R.id.themeSystemRadio)
        val themeLightRadio = dialogView.findViewById<android.widget.RadioButton>(R.id.themeLightRadio)
        val themeDarkRadio = dialogView.findViewById<android.widget.RadioButton>(R.id.themeDarkRadio)
        
        // Set current quality level
        val currentQuality = appSettingsStore.getImageQuality()
        updateQualityButtonStates(currentQuality, qualityLowButton, qualityMediumButton, qualityHighButton)
        
        hapticFeedbackSwitch.isChecked = appSettingsStore.getHapticFeedback()
        
        val currentFormat = appSettingsStore.getImageFormat()
        if (currentFormat == "png") {
            formatPngRadio.isChecked = true
        } else {
            formatJpegRadio.isChecked = true
        }
        setQualityControlsEnabled(currentFormat != "png", qualityLowButton, qualityMediumButton, qualityHighButton)
        
        val currentThemeMode = appSettingsStore.getThemeMode()
        when (currentThemeMode) {
            ThemeMode.SYSTEM -> themeSystemRadio.isChecked = true
            ThemeMode.LIGHT -> themeLightRadio.isChecked = true
            ThemeMode.DARK -> themeDarkRadio.isChecked = true
        }
        
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(dialogView)
        
        qualityLowButton.setOnClickListener {
            appSettingsStore.setImageQuality(0)
            updateQualityButtonStates(0, qualityLowButton, qualityMediumButton, qualityHighButton)
        }
        
        qualityMediumButton.setOnClickListener {
            appSettingsStore.setImageQuality(1)
            updateQualityButtonStates(1, qualityLowButton, qualityMediumButton, qualityHighButton)
        }
        
        qualityHighButton.setOnClickListener {
            appSettingsStore.setImageQuality(2)
            updateQualityButtonStates(2, qualityLowButton, qualityMediumButton, qualityHighButton)
        }
        
        hapticFeedbackSwitch.setOnCheckedChangeListener { _, isChecked ->
            appSettingsStore.setHapticFeedback(isChecked)
        }

        imageFormatGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.formatJpegRadio -> {
                    appSettingsStore.setImageFormat("jpg")
                    setQualityControlsEnabled(true, qualityLowButton, qualityMediumButton, qualityHighButton)
                }
                R.id.formatPngRadio -> {
                    appSettingsStore.setImageFormat("png")
                    setQualityControlsEnabled(false, qualityLowButton, qualityMediumButton, qualityHighButton)
                }
            }
        }
        
        themeModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val newTheme = when (checkedId) {
                R.id.themeLightRadio -> ThemeMode.LIGHT
                R.id.themeDarkRadio -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            appSettingsStore.setThemeMode(newTheme)
            applyThemeMode(newTheme)
        }
        
        dialog.show()
    }

    private fun applyThemeMode(mode: ThemeMode) {
        val uiMode = when (mode) {
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(uiMode)
    }

    private fun setQualityControlsEnabled(enabled: Boolean, lowBtn: MaterialButton, mediumBtn: MaterialButton, highBtn: MaterialButton) {
        lowBtn.isEnabled = enabled
        mediumBtn.isEnabled = enabled
        highBtn.isEnabled = enabled
        lowBtn.alpha = if (enabled) 1f else 0.5f
        mediumBtn.alpha = if (enabled) 1f else 0.5f
        highBtn.alpha = if (enabled) 1f else 0.5f
    }
    
    private fun updateQualityButtonStates(quality: Int, lowBtn: MaterialButton, mediumBtn: MaterialButton, highBtn: MaterialButton) {
        lowBtn.setBackgroundColor(if (quality == 0) ContextCompat.getColor(this, android.R.color.white) else ContextCompat.getColor(this, android.R.color.black))
        lowBtn.setTextColor(if (quality == 0) ContextCompat.getColor(this, android.R.color.black) else ContextCompat.getColor(this, android.R.color.white))
        
        mediumBtn.setBackgroundColor(if (quality == 1) ContextCompat.getColor(this, android.R.color.white) else ContextCompat.getColor(this, android.R.color.black))
        mediumBtn.setTextColor(if (quality == 1) ContextCompat.getColor(this, android.R.color.black) else ContextCompat.getColor(this, android.R.color.white))
        
        highBtn.setBackgroundColor(if (quality == 2) ContextCompat.getColor(this, android.R.color.white) else ContextCompat.getColor(this, android.R.color.black))
        highBtn.setTextColor(if (quality == 2) ContextCompat.getColor(this, android.R.color.black) else ContextCompat.getColor(this, android.R.color.white))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_LENS_FACING, lensFacing)
        outState.putBoolean(KEY_EXACT_PREVIEW_MODE, isExactPreviewMode)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            binding.permissionOverlay.visibility = View.GONE
            startCamera()
        } else {
            binding.permissionOverlay.visibility = View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                provider.unbindAll()
            } catch (e: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_CAMERA) {
            return
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isDestroyed || isFinishing) return@addListener
            
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            provider.unbindAll()
            try {
                val capture = imageCapture ?: return@addListener
                val viewPort = binding.previewView.viewPort
                camera = if (viewPort != null) {
                    val useCaseGroup = UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .addUseCase(preview)
                        .addUseCase(capture)
                        .build()
                    provider.bindToLifecycle(this, cameraSelector, useCaseGroup)
                } else {
                    provider.bindToLifecycle(this, cameraSelector, preview, capture)
                }
                bindZoomStateObservers()
                updateExposureResetUi()
            } catch (e: Exception) {
                android.util.Log.e("Clipcat", "Camera binding failed", e)
                if (isDestroyed || isFinishing) return@addListener
                
                Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show()
                // Falback to back camera if front fails
                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    lensFacing = CameraSelector.LENS_FACING_BACK
                    startCamera()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupZoomUi() {
        setZoomUiExpanded(expanded = false, animate = false)
        cancelZoomUiCollapse()

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isPinchZooming = true
                showZoomExpandedMode()
                cancelZoomUiCollapse()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val zoomState = camera?.cameraInfo?.zoomState?.value ?: return false
                val current = zoomState.zoomRatio
                val target = (current * detector.scaleFactor)
                    .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                camera?.cameraControl?.setZoomRatio(target)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isPinchZooming = false
                scheduleZoomUiCollapse()
            }
        })

        binding.previewView.setOnTouchListener { _, event ->
            handlePreviewTouch(event)
            true
        }

        binding.zoomSeekBar.max = 1000
        binding.zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingZoomSeekBar) {
                    return
                }

                val state = lastZoomState ?: return
                val zoomRatio = seekProgressToZoomRatio(progress, state.minZoomRatio, state.maxZoomRatio)
                camera?.cameraControl?.setZoomRatio(zoomRatio)
                showZoomExpandedMode()
                scheduleZoomUiCollapse()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                showZoomExpandedMode()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                scheduleZoomUiCollapse()
            }
        })

        val presetClickListener = View.OnClickListener { buttonView ->
            val ratio = (buttonView as? MaterialButton)?.tag as? Float ?: return@OnClickListener
            setSpecificZoomRatio(ratio)
            showZoomExpandedMode()
            scheduleZoomUiCollapse()
        }

        binding.zoomHalfButton.setOnClickListener(presetClickListener)
        binding.zoomOneButton.setOnClickListener(presetClickListener)
        binding.zoomTwoButton.setOnClickListener(presetClickListener)
        binding.zoomFourButton.setOnClickListener(presetClickListener)
        
        var startX = 0f
        var startProgress = 0
        binding.zoomCollapsedContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startProgress = binding.zoomSeekBar.progress
                    cancelZoomUiCollapse()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val progressDelta = (dx * 2).toInt() // Adjust sensitivity here
                    val newProgress = (startProgress + progressDelta).coerceIn(0, 1000)
                    
                    val state = lastZoomState
                    if (state != null) {
                        val zoomRatio = seekProgressToZoomRatio(newProgress, state.minZoomRatio, state.maxZoomRatio)
                        camera?.cameraControl?.setZoomRatio(zoomRatio)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - startX
                    if (kotlin.math.abs(dx) < tapSlopPx) {
                        showZoomExpandedMode()
                    }
                    scheduleZoomUiCollapse()
                    true
                }
                else -> false
            }
        }
        
        binding.exposureResetButton.setOnClickListener {
            camera?.cameraControl?.setExposureCompensationIndex(0)
            updateExposureResetUi()
        }
    }

    private fun bindZoomStateObservers() {
        val boundCamera = camera ?: return
        boundCamera.cameraInfo.zoomState.removeObservers(this)
        boundCamera.cameraInfo.zoomState.observe(this) { state ->
            lastZoomState = state
            binding.zoomControlsContainer.visibility = if (state.maxZoomRatio > state.minZoomRatio) {
                View.VISIBLE
            } else {
                View.GONE
            }

            val seekProgress = zoomRatioToSeekProgress(state.zoomRatio, state.minZoomRatio, state.maxZoomRatio)
            isUpdatingZoomSeekBar = true
            binding.zoomSeekBar.progress = seekProgress
            isUpdatingZoomSeekBar = false

            val presets = collectQuickZoomPresets(state)
            val presetButtons = listOf(
                binding.zoomHalfButton,
                binding.zoomOneButton,
                binding.zoomTwoButton,
                binding.zoomFourButton
            )
            presetButtons.forEachIndexed { index, button ->
                updatePresetButton(button, presets.getOrNull(index), state)
            }

            binding.zoomCollapsedText.text = formatZoomLabel(state.zoomRatio)
            binding.zoomCurrentText.text = formatZoomLabel(state.zoomRatio)
            binding.zoomMinText.text = formatZoomLabel(state.minZoomRatio)
            binding.zoomMaxText.text = formatZoomLabel(state.maxZoomRatio)
        }
    }

    private fun updateExposureResetUi() {
        val exposureState = camera?.cameraInfo?.exposureState
        if (exposureState == null || !exposureState.isExposureCompensationSupported) {
            binding.exposureResetButton.visibility = View.GONE
            return
        }

        val step = exposureState.exposureCompensationStep.toFloat()
        val evValue = exposureState.exposureCompensationIndex * step
        binding.exposureResetButton.text = String.format(Locale.US, "EV %+.1f", evValue)
        binding.exposureResetButton.visibility = if (exposureState.exposureCompensationIndex == 0) View.GONE else View.VISIBLE
    }

    private fun setSpecificZoomRatio(target: Float) {
        val state = lastZoomState ?: return
        if (!isZoomRatioSupported(target, state)) {
            return
        }
        camera?.cameraControl?.setZoomRatio(target)
    }

    private fun updatePresetButton(button: MaterialButton, ratio: Float?, state: ZoomState) {
        if (ratio == null) {
            button.visibility = View.GONE
            return
        }

        button.visibility = View.VISIBLE
        button.tag = ratio
        button.text = formatZoomLabel(ratio)

        val selected = kotlin.math.abs(state.zoomRatio - ratio) <= 0.08f
        if (selected) {
            button.setBackgroundColor(ContextCompat.getColor(this, android.R.color.white))
            button.setTextColor(ContextCompat.getColor(this, android.R.color.black))
        } else {
            button.setBackgroundColor(ContextCompat.getColor(this, android.R.color.black))
            button.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        }
    }

    private fun collectQuickZoomPresets(state: ZoomState): List<Float> {
        val desired = listOf(1.0f, 2.0f, 4.0f, 8.0f)
        val ratios = desired.filter { isZoomRatioSupported(it, state) }.toMutableList()

        if (ratios.isEmpty()) {
            ratios.add(state.minZoomRatio)
        }

        if (ratios.size < 4 && ratios.none { nearlyEqual(it, state.maxZoomRatio) }) {
            ratios.add(state.maxZoomRatio)
        }

        return ratios
            .distinctBy { (it * 100f).toInt() }
            .sorted()
            .take(4)
    }

    private fun nearlyEqual(a: Float, b: Float): Boolean {
        return kotlin.math.abs(a - b) < 0.05f
    }

    private fun isZoomRatioSupported(target: Float, state: ZoomState): Boolean {
        return target >= state.minZoomRatio && target <= state.maxZoomRatio
    }

    private fun formatZoomLabel(value: Float): String {
        val roundedToInt = kotlin.math.round(value)
        return if (kotlin.math.abs(value - roundedToInt) < 0.04f) {
            String.format(Locale.US, "%.0fx", roundedToInt)
        } else {
            String.format(Locale.US, "%.1fx", value)
        }
    }

    private fun handlePreviewTouch(event: MotionEvent) {
        scaleGestureDetector.onTouchEvent(event)

        if (event.pointerCount > 1 || isPinchZooming) {
            return
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchStartY = event.y
                touchStartExposureIndex = camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
                isVerticalDragExposure = false
                cancelZoomUiCollapse()
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaY = touchStartY - event.y
                if (!isVerticalDragExposure && kotlin.math.abs(deltaY) > dragZoomThresholdPx) {
                    isVerticalDragExposure = true
                }

                if (isVerticalDragExposure) {
                    applyVerticalDragExposure(deltaY)
                }
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                val movedTooFar = kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > tapSlopPx

                if (!isVerticalDragExposure && !movedTooFar) {
                    focusAt(event.x, event.y)
                } else if (isVerticalDragExposure) {
                    scheduleZoomUiCollapse()
                }

                isVerticalDragExposure = false
            }

            MotionEvent.ACTION_CANCEL -> {
                isVerticalDragExposure = false
                scheduleZoomUiCollapse()
            }
        }
    }

    private fun applyVerticalDragExposure(deltaY: Float) {
        val exposureState = camera?.cameraInfo?.exposureState ?: return
        if (!exposureState.isExposureCompensationSupported) {
            return
        }

        val previewHeight = binding.previewView.height.takeIf { it > 0 } ?: return
        val normalized = (deltaY / previewHeight.toFloat()).coerceIn(-1f, 1f)
        val range = exposureState.exposureCompensationRange
        val rangeSize = range.upper - range.lower
        if (rangeSize <= 0) {
            return
        }

        val target = (touchStartExposureIndex + normalized * rangeSize * 1.6f)
            .roundToInt()
            .coerceIn(range.lower, range.upper)

        camera?.cameraControl?.setExposureCompensationIndex(target)
        updateExposureResetUi()
    }

    private fun showZoomExpandedMode() {
        setZoomUiExpanded(expanded = true, animate = true)
    }

    private fun collapseZoomUiToLabel() {
        setZoomUiExpanded(expanded = false, animate = true)
    }

    private fun scheduleZoomUiCollapse() {
        cancelZoomUiCollapse()
        binding.zoomControlsContainer.postDelayed(zoomUiAutoCollapse, 3000)
    }

    private fun cancelZoomUiCollapse() {
        binding.zoomControlsContainer.removeCallbacks(zoomUiAutoCollapse)
    }

    private fun applyPreviewMode() {
        binding.previewView.scaleType = if (isExactPreviewMode) PreviewView.ScaleType.FIT_CENTER else PreviewView.ScaleType.FILL_CENTER
        binding.previewView.implementationMode = if (isExactPreviewMode) PreviewView.ImplementationMode.COMPATIBLE else PreviewView.ImplementationMode.PERFORMANCE

        binding.previewModeButton.text = if (isExactPreviewMode) "EXACT" else "FULL"

        if (camera != null && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            binding.previewView.post { startCamera() }
        }
    }

    private fun setZoomUiExpanded(expanded: Boolean, animate: Boolean) {
        if (isZoomUiExpanded == expanded && animate) {
            return
        }
        isZoomUiExpanded = expanded

        val allZoomChildren = listOf(
            binding.zoomCollapsedContainer,
            binding.zoomButtonsContainer,
            binding.zoomSeekBar,
            binding.zoomScaleLabelsRow
        )

        fun applyZoomStateVisibility() {
            binding.zoomCollapsedContainer.visibility = if (expanded) View.GONE else View.VISIBLE
            binding.zoomButtonsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.zoomSeekBar.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.zoomScaleLabelsRow.visibility = if (expanded) View.VISIBLE else View.GONE

            allZoomChildren.forEach { child ->
                child.alpha = 1f
                child.scaleX = 1f
                child.scaleY = 1f
            }
        }

        allZoomChildren.forEach { child ->
            child.animate().cancel()
        }

        val container = binding.zoomControlsContainer
        container.animate().cancel()

        if (!animate) {
            container.alpha = 1f
            container.scaleX = 1f
            container.scaleY = 1f
            applyZoomStateVisibility()
            return
        }

        container.animate()
            .alpha(0.86f)
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(95)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
            .withEndAction {
                applyZoomStateVisibility()
                container.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                    .start()
            }
            .start()
    }

    private fun focusAt(x: Float, y: Float) {
        val activeCamera = camera ?: return

        val meteringPoint = binding.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            meteringPoint,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        )
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        activeCamera.cameraControl.startFocusAndMetering(action)
        showFocusIndicator(x, y)
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        val ring = binding.focusIndicator
        ring.animate().cancel()

        val ringSize = ring.width.takeIf { it > 0 } ?: ring.layoutParams.width
        ring.x = x - ringSize / 2f
        ring.y = y - ringSize / 2f
        ring.alpha = 1f
        ring.scaleX = 1.2f
        ring.scaleY = 1.2f
        ring.visibility = View.VISIBLE

        ring.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(140)
            .withEndAction {
                ring.animate()
                    .alpha(0f)
                    .setStartDelay(480)
                    .setDuration(220)
                    .withEndAction {
                        ring.visibility = View.GONE
                        ring.alpha = 1f
                    }
                    .start()
            }
            .start()
    }

    private fun zoomRatioToSeekProgress(zoomRatio: Float, minZoom: Float, maxZoom: Float): Int {
        if (maxZoom <= minZoom) return 0

        val minLn = kotlin.math.ln(minZoom)
        val maxLn = kotlin.math.ln(maxZoom)
        val ratioLn = kotlin.math.ln(zoomRatio.coerceIn(minZoom, maxZoom))
        val normalized = (ratioLn - minLn) / (maxLn - minLn)
        return (normalized * binding.zoomSeekBar.max).toInt().coerceIn(0, binding.zoomSeekBar.max)
    }

    private fun seekProgressToZoomRatio(progress: Int, minZoom: Float, maxZoom: Float): Float {
        if (maxZoom <= minZoom) return minZoom

        val normalized = progress.toFloat() / binding.zoomSeekBar.max.toFloat()
        val minLn = kotlin.math.ln(minZoom)
        val maxLn = kotlin.math.ln(maxZoom)
        val zoomLn = minLn + normalized * (maxLn - minLn)
        return kotlin.math.exp(zoomLn)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                captureAndSend()
            }
            // Consume both down and up so volume keys behave as shutter while camera is open.
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun captureAndSend() {
        val config = pairingStore.load()
        if (config == null) {
            Toast.makeText(this, "Pair with PC first", Toast.LENGTH_SHORT).show()
            return
        }

        val capture = imageCapture
        if (capture == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        // Trigger haptic feedback
        if (appSettingsStore.getHapticFeedback()) {
            triggerHapticFeedback()
        }

        val imageFormat = appSettingsStore.getImageFormat()
        val fileExtension = if (imageFormat == "png") ".png" else ".jpg"
        val file = File.createTempFile("clipcat_capture_", fileExtension, cacheDir)
        imageCapture?.targetRotation = binding.previewView.display?.rotation ?: imageCapture?.targetRotation ?: 0
        val metadata = ImageCapture.Metadata()
        metadata.isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
        val options = ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Capture failed", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val sessionId = nextCaptureSessionId()
                    showCapturePreview(file, sessionId)
                    scope.launch {
                        sendImageWithConfig(config, file, sessionId)
                    }
                }
            }
        )
    }

    private fun nextCaptureSessionId(): Long {
        captureSessionCounter += 1
        activeCaptureSessionId = captureSessionCounter
        previewShownAtMs = 0L
        sendCompletedAtMs = 0L
        return activeCaptureSessionId
    }

    private fun showCapturePreview(file: File, sessionId: Long) {
        capturePreviewJob?.cancel()
        binding.capturePreviewContainer.visibility = View.GONE

        capturePreviewJob = scope.launch {
            val previewBitmap = withContext(Dispatchers.IO) {
                loadCapturePreviewBitmap(file)
            } ?: run {
                binding.capturePreviewContainer.visibility = View.GONE
                return@launch
            }

            if (!isActive || sessionId != activeCaptureSessionId) {
                previewBitmap.recycle()
                return@launch
            }

            val previewImage: ImageView = binding.capturePreviewImage
            val previewCard = binding.capturePreviewCard

            capturePreviewBitmap?.recycle()
            capturePreviewBitmap = previewBitmap
            previewImage.setImageBitmap(previewBitmap)
            binding.capturePreviewContainer.visibility = View.VISIBLE
            previewShownAtMs = SystemClock.elapsedRealtime()
            previewCard.animate().cancel()
            previewCard.alpha = 0f
            previewCard.translationY = 28f
            previewCard.scaleX = 0.96f
            previewCard.scaleY = 0.96f

            previewCard.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator(1.35f))
                .start()
        }
    }

    private suspend fun waitAndAnimateJointExit(sessionId: Long) {
        if (sessionId != activeCaptureSessionId) {
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        val previewWaitMs = if (binding.capturePreviewContainer.visibility == View.VISIBLE && previewShownAtMs > 0L) {
            (minPreviewVisibleMs - (nowMs - previewShownAtMs)).coerceAtLeast(0L)
        } else {
            0L
        }
        val sentWaitMs = if (sendCompletedAtMs > 0L) {
            (minSentVisibleMs - (nowMs - sendCompletedAtMs)).coerceAtLeast(0L)
        } else {
            0L
        }

        delay(maxOf(previewWaitMs, sentWaitMs))
        if (sessionId != activeCaptureSessionId) {
            return
        }

        val previewVisible = binding.capturePreviewContainer.visibility == View.VISIBLE
        if (previewVisible) {
            binding.capturePreviewCard.animate().cancel()
            binding.capturePreviewCard.animate()
                .alpha(0f)
                .translationY(-((binding.capturePreviewContainer.height / 2f) + (binding.capturePreviewCard.height / 2f) + 40f))
                .scaleX(0.97f)
                .scaleY(0.97f)
                .setDuration(jointExitDurationMs)
                .setInterpolator(AccelerateInterpolator(1.18f))
                .start()
        }

        binding.sendProgressContainer.animate().cancel()
        binding.sendProgressContainer.animate()
            .alpha(0f)
            .translationY(-((binding.sendProgressContainer.height) + 32f))
            .setDuration(jointExitDurationMs)
            .setInterpolator(AccelerateInterpolator(1.2f))
            .start()

        delay(jointExitDurationMs + 40L)
        if (sessionId == activeCaptureSessionId) {
            resetCaptureFeedbackUi()
        }
    }

    private fun resetCaptureFeedbackUi() {
        binding.capturePreviewCard.animate().cancel()
        binding.capturePreviewCard.alpha = 1f
        binding.capturePreviewCard.translationY = 0f
        binding.capturePreviewCard.scaleX = 1f
        binding.capturePreviewCard.scaleY = 1f
        capturePreviewBitmap?.recycle()
        capturePreviewBitmap = null
        binding.capturePreviewImage.setImageDrawable(null)
        binding.capturePreviewContainer.visibility = View.GONE

        binding.sendProgressContainer.animate().cancel()
        binding.sendProgressContainer.alpha = 1f
        binding.sendProgressContainer.translationY = 0f
        binding.sendProgressBar.visibility = View.VISIBLE
        binding.sendProgressBar.progress = 0
        binding.sendProgressText.text = "Sending 0%"
        binding.sendProgressContainer.visibility = View.GONE
    }

    private fun loadCapturePreviewBitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val sampleSize = calculateInSampleSize(bounds, 720, 720)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null
        val oriented = applyExifOrientation(file, decoded)
        if (oriented !== decoded) {
            decoded.recycle()
        }
        return oriented
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize.coerceAtLeast(1)
    }

    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator ?: return
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(50)
                }
            }
        } catch (e: Exception) {
            // Silent fail if vibration is not available
        }
    }

    private suspend fun sendImageWithConfig(config: PairingConfig, file: File, sessionId: Long) {
        val sendUiLockedToFinalState = AtomicBoolean(false)
        var sendSucceeded = false
        try {
            val keyBytes = Base64.getDecoder().decode(config.keyBase64)
            val imagePayload = withContext(Dispatchers.IO) {
                normalizeAndEncodePayload(file, config.fastTransfer)
            }

            withContext(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    sendUiLockedToFinalState.set(false)
                    binding.sendProgressContainer.animate().cancel()
                    binding.sendProgressContainer.visibility = View.VISIBLE
                    binding.sendProgressContainer.alpha = 1f
                    binding.sendProgressContainer.translationY = 0f
                    binding.sendProgressBar.visibility = View.VISIBLE
                    binding.sendProgressBar.progress = 0
                    binding.sendProgressText.text = "Sending 0%"
                    binding.captureButton.isEnabled = false
                }

                TcpImageSender.send(
                    config.ip,
                    config.port,
                    keyBytes,
                    imagePayload,
                    timeoutMs = 3000
                ) { percent ->
                    runOnUiThread {
                        if (sendUiLockedToFinalState.get()) {
                            return@runOnUiThread
                        }
                        binding.sendProgressBar.progress = percent
                        binding.sendProgressText.text = "Sending $percent%"
                    }
                }
            }

            sendUiLockedToFinalState.set(true)
            sendSucceeded = true
            sendCompletedAtMs = SystemClock.elapsedRealtime()
            binding.sendProgressBar.visibility = View.GONE
            binding.sendProgressText.text = "SENT ✓"
            waitAndAnimateJointExit(sessionId)
        } catch (_: SocketTimeoutException) {
            Toast.makeText(this, "PC Unreachable", Toast.LENGTH_SHORT).show()
        } catch (_: ConnectException) {
            Toast.makeText(this, "PC Unreachable", Toast.LENGTH_SHORT).show()
        } catch (ex: Exception) {
            Toast.makeText(this, "Send failed: ${ex.message}", Toast.LENGTH_SHORT).show()
        } finally {
            file.delete()
            binding.captureButton.isEnabled = true
            if (!sendSucceeded) {
                resetCaptureFeedbackUi()
            }
        }
    }

    private fun normalizeAndEncodePayload(file: File, fastTransfer: Boolean): ByteArray {
        val source = file.readBytes()
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size) ?: return source

        val oriented = applyExifOrientation(file, decoded)
        if (oriented !== decoded) {
            decoded.recycle()
        }

        return try {
            val format = appSettingsStore.getImageFormat()
            val out = java.io.ByteArrayOutputStream()
            if (format == "png") {
                // PNG is lossless; Android ignores quality for PNG compression.
                oriented.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            } else {
                val quality = appSettingsStore.getQualityValue()
                oriented.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
            }
            out.toByteArray()
        } finally {
            oriented.recycle()
        }
    }

    private fun applyExifOrientation(file: File, bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        return try {
            val orientation = FileInputStream(file).use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.preScale(-1f, 1f)
                    matrix.postRotate(270f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.preScale(-1f, 1f)
                    matrix.postRotate(90f)
                }
                else -> return bitmap
            }

            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: Exception) {
            bitmap
        }
    }

    companion object {
        const val ACTION_OPEN_CAMERA = "com.clipcat.app.action.OPEN_CAMERA"
        private const val KEY_LENS_FACING = "key_lens_facing"
        private const val KEY_EXACT_PREVIEW_MODE = "key_exact_preview_mode"
    }
}
