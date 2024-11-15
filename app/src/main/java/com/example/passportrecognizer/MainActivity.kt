package com.example.passportrecognizer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.passportrecognizer.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.huawei.hms.mlsdk.MLAnalyzerFactory
import com.huawei.hms.mlsdk.common.MLFrame
import com.huawei.hms.mlsdk.text.MLLocalTextSetting
import com.huawei.hms.mlsdk.text.MLTextAnalyzer
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding // Referencia para el binding de la vista
    private var imageUri: Uri? = null // URI de la imagen capturada o seleccionada
    private lateinit var imagePickerLauncher: ActivityResultLauncher<String> // Lanzador para la selección de imágenes

    private lateinit var analyzer: MLTextAnalyzer
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater) // Infla el layout con ViewBinding
        setContentView(binding.root) // Establece el contenido de la vista
        initializeImagePickerLauncher() // Inicializa el lanzador de selección de imágenes
        setupReadTextButtonListener() // Configura el listener del botón para leer texto
        setupCaptureImageButtonListener() // Configura el listener del botón para capturar imágenes
        setupShareButtonListener() //Configura el listener del botón para compartir texto

        cameraExecutor = Executors.newSingleThreadExecutor()

        val setting = MLLocalTextSetting.Factory()
            .setOCRMode(MLLocalTextSetting.OCR_DETECT_MODE) // Set languages that can be recognized.
            .setLanguage("ru").create()
        analyzer = MLAnalyzerFactory.getInstance().getLocalTextAnalyzer(setting)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (analyzer != null) {
                analyzer.stop()
            }
        } catch (e: IOException) {
            Log.e("MainActivity", "Error stopping analyzer", e)
        }
    }

    private fun startCamera() {
        binding.guideOverlay.visibility = View.VISIBLE
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            imageCapture =
                ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun checkCameraPermissionAndTakePhoto() {
        // Сначала проверяем разрешения
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Если PreviewView еще не активен, запускаем камеру
            if (binding.viewFinder.visibility != View.VISIBLE) {
                binding.viewFinder.visibility = View.VISIBLE
                binding.imageViewId.visibility = View.GONE
                startCamera()
            } else {
                // Если камера уже запущена, делаем снимок
                takePhoto()
            }
        } else {
            // Запрашиваем разрешения если их нет
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun takePhoto() {
        val photoFile = createImageFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    imageUri = savedUri

                    // Load the bitmap from the file and apply correct orientation
                    val originalBitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                    val rotatedBitmap = adjustBitmapRotation(originalBitmap, savedUri)

                    // Crop the rotated bitmap to the overlay area
                    val croppedBitmap = cropToOverlayArea(rotatedBitmap)

                    // Display the cropped image in imageViewId
                    binding.imageViewId.setImageBitmap(croppedBitmap)

                    // Pass the cropped bitmap to OCR
                    recognizeTextFromBitmap(croppedBitmap)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraX", "Photo capture failed: ${exc.message}", exc)
                }
            }
        )

        binding.viewFinder.visibility = View.GONE
        binding.imageViewId.visibility = View.VISIBLE
    }

    fun getLocationInView(targetView: View, parentView: View): IntArray {
        val targetLocation = IntArray(2)
        val parentLocation = IntArray(2)

        targetView.getLocationOnScreen(targetLocation)
        parentView.getLocationOnScreen(parentLocation)

        val relativeX = targetLocation[0] - parentLocation[0]
        val relativeY = targetLocation[1] - parentLocation[1]

        return intArrayOf(relativeX, relativeY)
    }

    private fun getOverlayCoordinates(): Rect {
        // Get the overlay's location on screen
        //val location = IntArray(2)
        //binding.guideOverlay.getLocationInSurface(location)
        val overlayCoords = getLocationInView(binding.guideOverlay, binding.viewFinder)
        // Convert the position and size of the overlay to the coordinates in the captured image
        val overlayX = overlayCoords[0]
        val overlayY = overlayCoords[1]
        //val overlayX = location[0]
        //val overlayY = location[1]
        val overlayWidth = binding.guideOverlay.width
        val overlayHeight = binding.guideOverlay.height

        return Rect(overlayX, overlayY, overlayX + overlayWidth, overlayY + overlayHeight)
    }

    private fun cropToOverlayArea(bitmap: Bitmap): Bitmap {
        val overlayRect = getOverlayCoordinates()

        // Calculate preview and image aspect ratios
        val previewAspectRatio = binding.viewFinder.width.toFloat() / binding.viewFinder.height
        val imageAspectRatio = bitmap.width.toFloat() / bitmap.height

        // Determine scale and offset to map overlay to bitmap dimensions
        val scaleX: Float
        val scaleY: Float
        var offsetX = 0
        var offsetY = 0

        if (previewAspectRatio > imageAspectRatio) {
            scaleX = bitmap.width.toFloat() / binding.viewFinder.width
            scaleY = scaleX
            offsetY = ((binding.viewFinder.height * scaleY - bitmap.height) / 2).toInt()
        } else {
            scaleY = bitmap.height.toFloat() / binding.viewFinder.height
            scaleX = scaleY
            offsetX = ((binding.viewFinder.width * scaleX - bitmap.width) / 2).toInt()
        }

        // Apply scale and offset to overlay coordinates
        val left = (overlayRect.left * scaleX - offsetX).toInt()
        val top = (overlayRect.top * scaleY - offsetY).toInt()
        val right = (overlayRect.right * scaleX - offsetX).toInt()
        val bottom = (overlayRect.bottom * scaleY - offsetY).toInt()

        Log.d("DebugScaled", "Scaled coordinates for cropping - Left: $left, Top: $top, Right: $right, Bottom: $bottom")

        // Ensure coordinates are within bounds
        val safeLeft = left.coerceAtLeast(0)
        val safeTop = top.coerceAtLeast(0)
        val safeRight = right.coerceAtMost(bitmap.width)
        val safeBottom = bottom.coerceAtMost(bitmap.height)

        // Crop the bitmap to the overlay area
        return Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)
    }

    private fun adjustBitmapRotation(bitmap: Bitmap, uri: Uri): Bitmap {
        val exif = contentResolver.openInputStream(uri)?.use { inputStream ->
            androidx.exifinterface.media.ExifInterface(inputStream)
        }
        val rotationDegrees = when (exif?.getAttributeInt(
            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
        )) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

        return if (rotationDegrees != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

    private fun recognizeTextFromBitmap(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)

        // Process the bitmap with your OCR analyzer
        val frame = MLFrame.fromBitmap(bitmap)
        val task = analyzer.asyncAnalyseFrame(frame)

        task.addOnSuccessListener { result ->
            displayRecognizedText(result.stringValue)
        }.addOnFailureListener { e ->
            handleTextRecognitionError(e)
        }
    }

    private fun recognizeTextFromImage(uri: Uri) {
        val image = InputImage.fromFilePath(this, uri)
        val frame = MLFrame.fromBitmap(image.bitmapInternal)

        val task = analyzer.asyncAnalyseFrame(frame)
        task.addOnSuccessListener {
            // Проверяем результаты построчно для лучшей отладки
            val stringBuilder = StringBuilder()
            for (block in task.result.blocks) {
                for (line in block.stringValue) {
                    stringBuilder.append(line).append("\n")
                    // Логируем каждую распознанную строку для отладки
                    Log.d("TextRecognition", "Распознанная строка: ${line}")
                    // Логируем значение confidence если доступно
                    //Log.d("TextRecognition", "Confidence: ${line.}")
                }
                stringBuilder.append("\n")
            }
            displayRecognizedText(task.result.stringValue)
        }.addOnFailureListener { e ->
            handleTextRecognitionError(e)
        }
    }


    // Inicializa el lanzador para seleccionar imágenes desde el almacenamiento del dispositivo
    private fun initializeImagePickerLauncher() {
        imagePickerLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                handleImagePickerResult(uri)
            }
    }

    // Configura el listener para el botón de lectura de texto
    private fun setupReadTextButtonListener() {
        binding.readTextButtonId.setOnClickListener {
            launchImagePicker() // Lanza el selector de imágenes
        }
    }

    private fun setupCaptureImageButtonListener() {
        binding.captureImageButtonId.setOnClickListener {
            checkCameraPermissionAndTakePhoto() // Call new method for CameraX capture
        }
    }

    // Configura el listener para el boton de compartir texto
    private fun setupShareButtonListener() {
        binding.shareButtonId.setOnClickListener {
            launchShare()
        }
    }

    //Lanza la funcion compartir texto
    private fun launchShare() {
        val textToShare = binding.resultText.text.toString()
        val intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, textToShare)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "Text to Share")
        }
        val shareIntent = Intent.createChooser(intent, "Share Text")
        startActivity(shareIntent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                binding.viewFinder.visibility = View.VISIBLE
                binding.imageViewId.visibility = View.GONE
                startCamera()
            } else {
                showToast("Camera permission is required to use this feature.")
            }
        }
    }

    // Lanza el selector de imágenes
    private fun launchImagePicker() {
        imagePickerLauncher.launch("image/*")
    }

    // Crea un archivo de imagen en el almacenamiento externo privado
    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "JPEG_${timeStamp}_", ".jpg", storageDir
        ).apply {
            imageUri = Uri.fromFile(this) // Guarda el URI del archivo creado
        }
    }

    // Maneja el resultado de la selección de imágenes
    private fun handleImagePickerResult(uri: Uri?) {
        uri ?: return showToast("Image selection failed. Please try again.")
        imageUri = uri
        binding.imageViewId.setImageURI(uri) // Muestra la imagen seleccionada
        recognizeTextFromImage(uri) // Inicia el reconocimiento de texto en la imagen seleccionada
    }

    // Muestra el texto reconocido en la UI
    private fun displayRecognizedText(text: String) {
        if (text.isBlank()) {
            showToast("Текст на изображении не найден.") // Notifica al usuario si no se encontró texto
        } else {
            // Логируем весь распознанный текст
            Log.d("TextRecognition", "Весь распознанный текст:\n$text")

            // Логируем коды символов для отладки
            Log.d("TextRecognition", "Коды символов:")
            text.forEach { char ->
                Log.d("TextRecognition", "Символ '$char': ${char.code}")
            }
            binding.resultText.text = text // Muestra el texto reconocido
        }
    }

    // Maneja errores en el reconocimiento de texto
    private fun handleTextRecognitionError(e: Exception) {
        showToast("Ошибка распознавания текста: ${e.localizedMessage}")
        Log.e("TextRecognition", "Ошибка распознавания", e)
        e.printStackTrace()
    }


    // Muestra un mensaje Toast
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE =
            101 // Código de solicitud de permiso de cámara
    }
}