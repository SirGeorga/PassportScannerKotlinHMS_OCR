package com.example.passportrecognizer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

    //private lateinit var cameraCaptureLauncher: ActivityResultLauncher<Uri> // Lanzador para la captura de fotos
    //private val tessBaseAPI = TessBaseAPI()
    private lateinit var analyzer: MLTextAnalyzer
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater) // Infla el layout con ViewBinding
        setContentView(binding.root) // Establece el contenido de la vista
        initializeImagePickerLauncher() // Inicializa el lanzador de selección de imágenes
        //initializeCameraCaptureLauncher() // Inicializa el lanzador de captura de cámara
        setupReadTextButtonListener() // Configura el listener del botón para leer texto
        setupCaptureImageButtonListener() // Configura el listener del botón para capturar imágenes
        setupShareButtonListener() //Configura el listener del botón para compartir texto

        cameraExecutor = Executors.newSingleThreadExecutor()

        val setting = MLLocalTextSetting.Factory()
            .setOCRMode(MLLocalTextSetting.OCR_DETECT_MODE) // Set languages that can be recognized.
            .setLanguage("ru")
            .create()
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
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun checkCameraPermissionAndTakePhoto() {
        // Сначала проверяем разрешения
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
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
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
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
                    binding.imageViewId.setImageURI(savedUri)
                    recognizeTextFromImage(savedUri)
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d("CameraX", msg)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraX", "Photo capture failed: ${exc.message}", exc)
                }
            }
        )
        // Показать захваченное изображение
        binding.viewFinder.visibility = View.GONE
        binding.imageViewId.visibility = View.VISIBLE
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
        }
            .addOnFailureListener { e ->
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

    /*
    // Inicializa el lanzador para capturar imágenes con la cámara
    private fun initializeCameraCaptureLauncher() {
        cameraCaptureLauncher =
            registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                if (success) {
                    imageUri?.let { uri ->
                        binding.imageViewId.setImageURI(uri) // Muestra la imagen capturada
                        recognizeTextFromImage(uri) // Inicia el reconocimiento de texto en la imagen
                    }
                } else {
                    showToast("Image capture failed. Please try again.") // Muestra error si la captura falla

                }
            }
    }*/

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

    /*
    // Configura el listener para el botón de captura de imagen
    private fun setupCaptureImageButtonListener() {
        binding.captureImageButtonId.setOnClickListener {
            checkCameraPermissionAndLaunchCamera() // Verifica permisos y lanza la cámara
        }
    }

     */

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
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
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

    /*
    // Verifica el permiso de cámara y, si está concedido, lanza la cámara; de lo contrario, solicita el permiso
    private fun checkCameraPermissionAndLaunchCamera() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        } else {
            launchCamera() // Lanza la cámara si el permiso ya está concedido
        }
    }


    // Maneja el resultado de la solicitud de permisos
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCamera() // Lanza la cámara si el permiso es concedido
            } else {
                showToast("Camera permission is required to use this feature.") // Muestra error si el permiso es denegado
            }
        }
    }


    private fun takePhoto() {
        val photoFile = createImageFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    imageUri = Uri.fromFile(photoFile)
                    recognizeTextFromImage(imageUri!!)  // Pass image URI for OCR
                }

                override fun onError(exception: ImageCaptureException) {
                    showToast("Failed to capture image: ${exception.message}")
                    Log.e("CameraX", "Photo capture failed", exception)
                }
            })
    }
    */


    // Lanza el selector de imágenes
    private fun launchImagePicker() {
        imagePickerLauncher.launch("image/*")
    }

    /*
    // Crea una intención para capturar una imagen y lanza la cámara
    private fun launchCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.resolveActivity(packageManager)?.also {
            val photoFile: File? = try {
                createImageFile() // Crea un archivo para guardar la imagen
            } catch (ex: IOException) {
                null // Maneja la excepción si la creación del archivo falla
            }
            photoFile?.also {
                val photoURI: Uri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.provider",
                    it
                )
                imageUri = photoURI
                cameraCaptureLauncher.launch(photoURI) // Lanza la cámara con el URI del archivo
            }
        }
    }

     */

    // Crea un archivo de imagen en el almacenamiento externo privado
    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "JPEG_${timeStamp}_",
            ".jpg",
            storageDir
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