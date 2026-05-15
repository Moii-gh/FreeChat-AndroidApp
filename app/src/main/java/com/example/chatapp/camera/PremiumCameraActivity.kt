package com.example.chatapp.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.util.SafeImageLoader
import com.example.chatapp.util.setPressAnimation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor

class PremiumCameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var previewShell: FrameLayout
    private lateinit var controlsPanel: View
    private lateinit var shutterButton: FrameLayout
    private lateinit var flipButton: ImageButton
    private lateinit var galleryStack: FrameLayout
    private lateinit var galleryFront: ImageView
    private lateinit var galleryBack: ImageView
    private lateinit var formatToggle: View
    private lateinit var formatOptions: View
    private lateinit var formatSelectedIndicator: View
    private lateinit var formatGlassHighlight: View
    private lateinit var formatButtons: Map<CameraFrameFormat, TextView>
    private lateinit var mainExecutor: Executor
    private lateinit var zoomGestureDetector: ScaleGestureDetector

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var outputUri: Uri? = null
    private var outputFile: File? = null
    private var isCapturing = false
    private var selectedFrameFormat = CameraFrameFormat.Square
    private var pinchZoomRatio = 1f

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val cameraGranted = results[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)
            if (cameraGranted) {
                startCamera()
            } else {
                Toast.makeText(this, LocaleHelper.getString(this, "button_camera"), Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_CANCELED)
                finish()
                return@registerForActivityResult
            }
            loadRecentGalleryImages()
        }

    private val galleryPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_URI, uri.toString()))
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        setContentView(R.layout.activity_premium_camera)

        mainExecutor = ContextCompat.getMainExecutor(this)
        readOutputTarget()
        bindViews()
        configureGlass()
        configureActions()
        configureZoomGestures()
        animateIntro()
        requestCameraAndGalleryPermissions()
    }

    override fun onDestroy() {
        if (::formatGlassHighlight.isInitialized) {
            formatGlassHighlight.animate().cancel()
        }
        if (::formatSelectedIndicator.isInitialized) {
            formatSelectedIndicator.animate().cancel()
        }
        cameraProvider?.unbindAll()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::zoomGestureDetector.isInitialized && isTouchInsidePreview(event)) {
            zoomGestureDetector.onTouchEvent(event)
            if (event.pointerCount > 1 || zoomGestureDetector.isInProgress) {
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun readOutputTarget() {
        outputUri = intent.getStringExtra(EXTRA_OUTPUT_URI)?.toUri()
        outputFile = intent.getStringExtra(EXTRA_OUTPUT_FILE_PATH)
            ?.let(::File)
            ?: File(cacheDir, "premium_camera_${System.currentTimeMillis()}.jpg").also { file ->
                outputUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    file
                )
            }
    }

    private fun bindViews() {
        val root = findViewById<View>(R.id.premiumCameraRoot)
        previewView = findViewById(R.id.cameraPreview)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewShell = findViewById(R.id.cameraPreviewShell)
        controlsPanel = findViewById(R.id.cameraControlsPanel)
        shutterButton = findViewById(R.id.shutterButton)
        flipButton = findViewById(R.id.btnFlipCamera)
        galleryStack = findViewById(R.id.galleryStack)
        galleryFront = findViewById(R.id.galleryFront)
        galleryBack = findViewById(R.id.galleryBack)
        formatToggle = findViewById(R.id.cameraFormatToggle)
        formatOptions = findViewById(R.id.formatOptions)
        formatSelectedIndicator = findViewById(R.id.formatSelectedIndicator)
        formatGlassHighlight = findViewById(R.id.formatGlassHighlight)
        formatButtons = mapOf(
            CameraFrameFormat.Square to findViewById(R.id.formatSquare),
            CameraFrameFormat.ThreeFour to findViewById(R.id.formatThreeFour),
            CameraFrameFormat.NineSixteen to findViewById(R.id.formatNineSixteen),
            CameraFrameFormat.Full to findViewById(R.id.formatFull)
        )

        val baseBottomPadding = controlsPanel.paddingBottom
        val baseToggleTopMargin = (formatToggle.layoutParams as ConstraintLayout.LayoutParams).topMargin
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            controlsPanel.setPadding(
                controlsPanel.paddingLeft,
                controlsPanel.paddingTop,
                controlsPanel.paddingRight,
                baseBottomPadding + systemBars.bottom
            )
            (formatToggle.layoutParams as ConstraintLayout.LayoutParams).let { params ->
                params.topMargin = baseToggleTopMargin + systemBars.top
                formatToggle.layoutParams = params
            }
            insets
        }
    }

    private fun configureGlass() {
        galleryFront.clipToOutline = true
        galleryBack.clipToOutline = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            formatGlassHighlight.setRenderEffect(
                RenderEffect.createBlurEffect(0.7f, 0.7f, Shader.TileMode.CLAMP)
            )
        }
        formatSelectedIndicator.translationZ = 1f
        formatOptions.translationZ = 8f
        formatOptions.bringToFront()
    }

    private fun configureActions() {
        flipButton.setPressAnimation(0.9f, 1f)
        flipButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            flipButton.animate()
                .rotationBy(180f)
                .setDuration(360L)
                .setInterpolator(OvershootInterpolator(1.35f))
                .start()
            switchCamera()
        }

        shutterButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animateShutterPressed(view)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateShutterReleased(view)
            }
            false
        }
        shutterButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            capturePhoto()
        }

        galleryStack.setPressAnimation(0.93f, 1f)
        galleryStack.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            galleryPicker.launch("image/*")
        }

        formatButtons.forEach { (format, button) ->
            button.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                applyFrameFormat(format, animate = true)
            }
            button.setOnTouchListener(formatDragTouchListener)
        }
        formatToggle.setOnTouchListener(formatDragTouchListener)
        formatOptions.setOnTouchListener(formatDragTouchListener)
        formatToggle.post {
            applyFrameFormat(selectedFrameFormat, animate = false)
            animateGlassHighlight()
        }
    }

    private fun configureZoomGestures() {
        zoomGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    pinchZoomRatio = boundCamera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                    return boundCamera != null
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val camera = boundCamera ?: return false
                    val zoomState = camera.cameraInfo.zoomState.value ?: return false
                    val targetZoom = (pinchZoomRatio * detector.scaleFactor)
                        .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                    pinchZoomRatio = targetZoom
                    camera.cameraControl.setZoomRatio(targetZoom)
                    return true
                }
            }
        )
    }

    private fun isTouchInsidePreview(event: MotionEvent): Boolean {
        val previewBounds = Rect()
        previewShell.getGlobalVisibleRect(previewBounds)
        return previewBounds.contains(event.rawX.toInt(), event.rawY.toInt())
    }

    private fun animateIntro() {
        previewShell.alpha = 0f
        previewShell.scaleX = 0.985f
        previewShell.scaleY = 0.985f
        formatToggle.alpha = 0f
        formatToggle.translationY = -8f * resources.displayMetrics.density
        controlsPanel.alpha = 0f
        controlsPanel.translationY = 42f * resources.displayMetrics.density

        formatToggle.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(80L)
            .setDuration(360L)
            .setInterpolator(DecelerateInterpolator(1.7f))
            .start()

        previewShell.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(70L)
            .setDuration(520L)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .start()

        controlsPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(150L)
            .setDuration(520L)
            .setInterpolator(DecelerateInterpolator(1.9f))
            .start()
    }

    private fun applyFrameFormat(format: CameraFrameFormat, animate: Boolean) {
        if (format == selectedFrameFormat && animate) return
        selectedFrameFormat = format

        val root = findViewById<ConstraintLayout>(R.id.premiumCameraRoot)
        if (animate) {
            TransitionManager.beginDelayedTransition(
                root,
                AutoTransition().apply {
                    duration = 260L
                    interpolator = DecelerateInterpolator(1.8f)
                }
            )
        }

        (previewShell.layoutParams as ConstraintLayout.LayoutParams).let { params ->
            params.dimensionRatio = format.dimensionRatio
            previewShell.layoutParams = params
        }

        formatButtons.forEach { (buttonFormat, button) ->
            val isSelected = buttonFormat == format
            button.setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
            button.alpha = if (isSelected) 1f else 0.86f
            button.translationZ = 8f
        }

        moveFormatIndicator(format, animate)
    }

    private val formatDragTouchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                selectFrameFormatAt(event.rawX)
                true
            }
            MotionEvent.ACTION_UP -> {
                formatToggle.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                formatSelectedIndicator.animate()
                    .scaleX(1.04f)
                    .scaleY(1.08f)
                    .setDuration(90L)
                    .withEndAction {
                        formatSelectedIndicator.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(170L)
                            .setInterpolator(OvershootInterpolator(1.35f))
                            .start()
                    }
                    .start()
                true
            }
            MotionEvent.ACTION_CANCEL -> true
            else -> false
        }
    }

    private fun selectFrameFormatAt(rawX: Float) {
        val optionLocation = IntArray(2)
        formatOptions.getLocationOnScreen(optionLocation)
        val localX = rawX - optionLocation[0]

        val targetFormat = formatButtons.minByOrNull { (_, button) ->
            val center = button.left + button.width / 2f
            kotlin.math.abs(localX - center)
        }?.key ?: return

        if (targetFormat != selectedFrameFormat) {
            formatToggle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            applyFrameFormat(targetFormat, animate = true)
        }
    }

    private fun moveFormatIndicator(format: CameraFrameFormat, animate: Boolean) {
        val target = formatButtons[format] ?: return
        val targetX = formatOptions.x + target.left
        val targetWidth = target.width.takeIf { it > 0 } ?: target.layoutParams.width
        val params = formatSelectedIndicator.layoutParams

        if (params.width != targetWidth) {
            params.width = targetWidth
            formatSelectedIndicator.layoutParams = params
        }

        formatSelectedIndicator.animate().cancel()
        if (animate) {
            formatSelectedIndicator.animate()
                .translationX(targetX)
                .scaleX(1.035f)
                .scaleY(1.08f)
                .setDuration(170L)
                .setInterpolator(DecelerateInterpolator(1.9f))
                .withEndAction {
                    formatSelectedIndicator.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(190L)
                        .setInterpolator(OvershootInterpolator(1.45f))
                        .start()
                }
                .start()
        } else {
            formatSelectedIndicator.translationX = targetX
            formatSelectedIndicator.scaleX = 1f
            formatSelectedIndicator.scaleY = 1f
        }
    }

    private fun animateGlassHighlight() {
        formatGlassHighlight.post {
            val travel = (formatToggle.width - formatGlassHighlight.width).coerceAtLeast(0)
            formatGlassHighlight.translationX = -formatGlassHighlight.width * 0.25f
            formatGlassHighlight.animate()
                .translationX(travel + formatGlassHighlight.width * 0.18f)
                .alpha(0.28f)
                .setStartDelay(420L)
                .setDuration(1800L)
                .setInterpolator(DecelerateInterpolator(1.2f))
                .withEndAction {
                    formatGlassHighlight.alpha = 0.42f
                    animateGlassHighlight()
                }
                .start()
        }
    }

    private fun requestCameraAndGalleryPermissions() {
        val permissions = buildList {
            if (!hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            galleryPermissionNames().filterTo(this) { !hasPermission(it) }
        }

        if (permissions.isEmpty()) {
            startCamera()
            loadRecentGalleryImages()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                cameraProvider = providerFuture.get()
                bindCameraUseCases(animate = false)
            },
            mainExecutor
        )
    }

    private fun bindCameraUseCases(animate: Boolean) {
        val provider = cameraProvider ?: return
        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder()
            .setTargetRotation(targetRotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(targetRotation)
            .build()

        runCatching {
            provider.unbindAll()
            boundCamera = provider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
        }.onFailure {
            boundCamera = null
            Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show()
        }

        if (animate) {
            previewShell.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator(1.7f))
                .start()
        }
    }

    private fun switchCamera() {
        val provider = cameraProvider ?: return
        val nextSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        if (!provider.hasCamera(nextSelector)) return

        cameraSelector = nextSelector
        previewShell.animate()
            .alpha(0.72f)
            .scaleX(0.985f)
            .scaleY(0.985f)
            .setDuration(120L)
            .withEndAction { bindCameraUseCases(animate = true) }
            .start()
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val file = outputFile ?: return
        if (isCapturing) return
        isCapturing = true

        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    isCapturing = false
                    val resultUri = outputUri ?: file.toUri()
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_URI, resultUri.toString()))
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing = false
                    Toast.makeText(this@PremiumCameraActivity, "Could not save photo", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun loadRecentGalleryImages() {
        if (!hasAnyGalleryPermission()) {
            showGalleryPlaceholder()
            return
        }

        lifecycleScope.launch {
            val images = withContext(Dispatchers.IO) { queryRecentImages(limit = 2) }
            if (images.isEmpty()) {
                showGalleryPlaceholder()
            } else {
                galleryStack.visibility = View.VISIBLE
                galleryFront.setPadding(0, 0, 0, 0)
                galleryFront.imageTintList = null
                SafeImageLoader.loadUri(galleryFront, images.first(), galleryThumbSize(), galleryThumbSize())
                galleryFront.alpha = 0f
                galleryFront.translationX = 10f * resources.displayMetrics.density
                galleryFront.animate().alpha(1f).translationX(0f).setDuration(260L).start()

                if (images.size > 1) {
                    SafeImageLoader.loadUri(galleryBack, images[1], galleryThumbSize(), galleryThumbSize())
                    galleryBack.visibility = View.VISIBLE
                    galleryBack.alpha = 0f
                    galleryBack.animate().alpha(0.76f).setDuration(320L).start()
                } else {
                    galleryBack.visibility = View.INVISIBLE
                }
            }
        }
    }

    private fun galleryThumbSize(): Int =
        galleryFront.width
            .takeIf { it > 0 }
            ?: galleryFront.height.takeIf { it > 0 }
            ?: (64 * resources.displayMetrics.density).toInt()

    private fun queryRecentImages(limit: Int): List<Uri> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val images = mutableListOf<Uri>()

        contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && images.size < limit) {
                val id = cursor.getLong(idColumn)
                images += Uri.withAppendedPath(collection, id.toString())
            }
        }
        return images
    }

    private fun showGalleryPlaceholder() {
        galleryStack.visibility = View.VISIBLE
        galleryBack.visibility = View.VISIBLE
        galleryBack.setImageDrawable(null)
        galleryFront.setImageResource(R.drawable.ic_photo_new)
        galleryFront.setPadding(
            (10 * resources.displayMetrics.density).toInt(),
            (10 * resources.displayMetrics.density).toInt(),
            (10 * resources.displayMetrics.density).toInt(),
            (10 * resources.displayMetrics.density).toInt()
        )
    }

    private fun animateShutterPressed(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(85L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateShutterReleased(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(210L)
            .setInterpolator(OvershootInterpolator(1.9f))
            .start()
    }

    private fun galleryPermissionNames(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasAnyGalleryPermission(): Boolean = galleryPermissionNames().any(::hasPermission)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_OUTPUT_URI = "com.example.chatapp.camera.OUTPUT_URI"
        const val EXTRA_OUTPUT_FILE_PATH = "com.example.chatapp.camera.OUTPUT_FILE_PATH"
        const val EXTRA_RESULT_URI = "com.example.chatapp.camera.RESULT_URI"

        fun newIntent(context: Context, outputUri: Uri, outputFile: File): Intent =
            Intent(context, PremiumCameraActivity::class.java).apply {
                putExtra(EXTRA_OUTPUT_URI, outputUri.toString())
                putExtra(EXTRA_OUTPUT_FILE_PATH, outputFile.absolutePath)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }

    private enum class CameraFrameFormat(val dimensionRatio: String?) {
        Square("1:1"),
        ThreeFour("3:4"),
        NineSixteen("9:16"),
        Full(null)
    }
}
