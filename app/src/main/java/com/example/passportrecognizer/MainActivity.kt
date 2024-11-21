package com.example.passportrecognizer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.example.passportrecognizer.databinding.ActivityMainBinding
import com.google.gson.Gson
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.huawei.hms.mlsdk.MLAnalyzerFactory
import com.huawei.hms.mlsdk.common.MLFrame
import com.huawei.hms.mlsdk.text.MLLocalTextSetting
import com.huawei.hms.mlsdk.text.MLTextAnalyzer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageUri: Uri? = null
    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>
    private lateinit var analyzer: MLTextAnalyzer
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var rotatedBitmapFace: Bitmap
    private lateinit var rotatedBitmapBack: Bitmap
    private val url = "https://crm.tpu.ru/bitrix/js/LocalApps/olimpHandler/wh.php"

    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var scannedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initializeImagePickerLauncher()
        initListenners()

        cameraExecutor = Executors.newSingleThreadExecutor()

        val setting = MLLocalTextSetting.Factory()
            .setOCRMode(MLLocalTextSetting.OCR_DETECT_MODE) // Set languages that can be recognized.
            .setLanguage("ru").create()
        analyzer = MLAnalyzerFactory.getInstance().getLocalTextAnalyzer(setting)

        val bottomNavigationView = binding.bottomNavigationView

        bottomNavigationView.selectedItemId = R.id.addClent

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.addClent -> true // Текущая Activity, ничего не делаем
                R.id.historyFragment -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    finish()
                    true
                }

                else -> false
            }
        }

        scannerLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                handleActivityResult(result)
            }
    }

    private fun startDocumentScanning() {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER).build()

        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { intentSender: IntentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }.addOnFailureListener { e: Exception ->
                e.message?.let { showToast(it) }
                checkCameraPermissionAndTakePhoto()
            }
    }

    private fun handleActivityResult(activityResult: ActivityResult) {
        val resultCode = activityResult.resultCode
        val result = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)

        if (resultCode == Activity.RESULT_OK && result != null) {
            result.pages?.firstOrNull()?.let { page ->
                Glide.with(this).load(page.imageUri).into(binding.imageViewId)
                binding.imageViewId.visibility = View.VISIBLE
                // Загрузка Bitmap для дальнейшей обработки
                contentResolver.openInputStream(page.imageUri)?.use { inputStream ->
                    scannedBitmap = BitmapFactory.decodeStream(inputStream)

                    scannedBitmap?.let { bitmap ->
                        // Используйте bitmap, безопасно после проверки на null
                        prepareToRecognition(bitmap)
                    }
                }
                //resultInfo.text = getString(R.string.scan_successful)
            }
        } else {/*resultInfo.text = getString(
                if (resultCode == Activity.RESULT_CANCELED) R.string.error_scanner_cancelled
                else R.string.error_default_message
            )*/
        }
    }

    private fun initListenners() {
        binding.readTextButtonId.setOnClickListener {
            launchImagePicker() // Lanza el selector de imágenes
        }

        binding.captureImageButtonId.setOnClickListener {
            startDocumentScanning()//checkCameraPermissionAndTakePhoto()
        }

        binding.shareButtonId.setOnClickListener {
            if (binding.rgSex.checkedRadioButtonId != -1 && binding.rgGrade.checkedRadioButtonId != -1 && binding.etEventID.text.isNotEmpty()) launchShare()
            else showToast("Проверьте, что выбраны пол и класс участника, ID мероприятия")
        }

        binding.photoFaceFingernail.setOnClickListener {
            showFaceGuideLines()
        }

        binding.photoBackFingernail.setOnClickListener {
            showBackGuideLines()
        }
    }

    private fun showFaceGuideLines() {
        binding.layoutGuideFaceContainer.visibility = View.VISIBLE
        binding.layoutGuideBackContainer.visibility = View.GONE
    }

    private fun showBackGuideLines() {
        binding.layoutGuideFaceContainer.visibility = View.GONE
        binding.layoutGuideBackContainer.visibility = View.VISIBLE
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
                var guideViews: List<View>
                // Если камера уже запущена, делаем снимок
                if (binding.layoutGuideFaceContainer.visibility == View.VISIBLE) {
                    guideViews = listOf(
                        binding.guideOverlay,
                        binding.guideFamilyName,
                        binding.guideName,
                        binding.guideSurname,
                        binding.guideBirthDate,
                        binding.guidePhoto
                    )
                    takePhoto(guideViews)
                } else {
                    guideViews = listOf(
                        binding.guideOverlay,
                        binding.guideIDNumber,
                        binding.guideBirthPlace,
                        binding.guideIssued,
                        binding.guideDocDate
                    )
                    takePhoto(guideViews)
                }
            }
        } else {
            // Запрашиваем разрешения если их нет
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun prepareToRecognition(bitmap: Bitmap) {
        val guideViews: List<View>
        if (binding.layoutGuideFaceContainer.isVisible) {
            guideViews = listOf(
                binding.guideOverlay,
                binding.guideFamilyName,
                binding.guideName,
                binding.guideSurname,
                binding.guideBirthDate,
                binding.guidePhoto
            )
            guideViews.forEachIndexed { index, guideView ->
                val overlayRect = getOverlayCoordinates(
                    getLocationInView(
                        guideView, binding.imageViewId
                    ), guideView
                )
                val croppedBitmap = cropToOverlayArea(bitmap, overlayRect, binding.imageViewId)


                recognizeTextFromBitmap(croppedBitmap, index, true)

                // Process croppedBitmap according to the specific guide
                when (index) {
                    0 -> binding.photoFaceFingernail.setImageBitmap(croppedBitmap)
                }
            }
        } else {
            guideViews = listOf(
                binding.guideOverlay,
                binding.guideIDNumber,
                binding.guideBirthPlace,
                binding.guideIssued,
                binding.guideDocDate
            )
            guideViews.forEachIndexed { index, guideView ->
                val overlayRect = getOverlayCoordinates(
                    getLocationInView(
                        guideView, binding.imageViewId
                    ), guideView
                )
                val croppedBitmap = cropToOverlayArea(bitmap, overlayRect, binding.imageViewId)
                recognizeTextFromBitmap(croppedBitmap, index, false)

                when (index) {
                    0 -> binding.photoBackFingernail.setImageBitmap(croppedBitmap)
                }
            }
        }
    }

       private fun takePhoto(guideViews: List<View>) {
        val photoFile = createImageFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    imageUri = savedUri
                    val originalBitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                    if (binding.layoutGuideFaceContainer.isVisible) {
                        rotatedBitmapFace = adjustBitmapRotation(originalBitmap, savedUri)
                        guideViews.forEachIndexed { index, guideView ->
                            val overlayRect = getOverlayCoordinates(
                                getLocationInView(
                                    guideView, binding.viewFinder
                                ), guideView
                            )
                            val croppedBitmap = cropToOverlayArea(
                                rotatedBitmapFace, overlayRect, binding.viewFinder
                            )
                            recognizeTextFromBitmap(croppedBitmap, index, true)

                            // Process croppedBitmap according to the specific guide
                            when (index) {
                                0 -> binding.photoFaceFingernail.setImageBitmap(croppedBitmap)
                            }
                        }
                        showBackGuideLines()
                    } else {
                        rotatedBitmapBack = adjustBitmapRotation(originalBitmap, savedUri)
                        guideViews.forEachIndexed { index, guideView ->
                            val overlayRect = getOverlayCoordinates(
                                getLocationInView(
                                    guideView, binding.viewFinder
                                ), guideView
                            )
                            val croppedBitmap = cropToOverlayArea(
                                rotatedBitmapBack, overlayRect, binding.viewFinder
                            )
                            recognizeTextFromBitmap(croppedBitmap, index, false)

                            when (index) {
                                0 -> binding.photoBackFingernail.setImageBitmap(croppedBitmap)
                            }
                            showFaceGuideLines()
                        }
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraX", "Photo capture failed: ${exc.message}", exc)
                }
            })
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

    private fun getOverlayCoordinates(overlayCoords: IntArray, view: View): Rect {
        // Convert the position and size of the overlay to the coordinates in the captured image
        val overlayX = overlayCoords[0]
        val overlayY = overlayCoords[1]
        val overlayWidth = view.width
        val overlayHeight = view.height

        return Rect(overlayX, overlayY, overlayX + overlayWidth, overlayY + overlayHeight)
    }

    private fun cropToOverlayArea(bitmap: Bitmap, overlayRect: Rect, parentView: View): Bitmap {
        // Calculate preview and image aspect ratios
        val previewAspectRatio = parentView.width.toFloat() / parentView.height
        val imageAspectRatio = bitmap.width.toFloat() / bitmap.height

        // Determine scale and offset to map overlay to bitmap dimensions
        val scaleX: Float
        val scaleY: Float
        var offsetX = 0
        var offsetY = 0

        if (previewAspectRatio > imageAspectRatio) {
            scaleX = bitmap.width.toFloat() / parentView.width
            scaleY = scaleX
            offsetY = ((parentView.height * scaleY - bitmap.height) / 2).toInt()
        } else {
            scaleY = bitmap.height.toFloat() / parentView.height
            scaleX = scaleY
            offsetX = ((parentView.width * scaleX - bitmap.width) / 2).toInt()
        }

        // Apply scale and offset to overlay coordinates
        val left = (overlayRect.left * scaleX - offsetX).toInt()
        val top = (overlayRect.top * scaleY - offsetY).toInt()
        val right = (overlayRect.right * scaleX - offsetX).toInt()
        val bottom = (overlayRect.bottom * scaleY - offsetY).toInt()

        // Ensure coordinates are within bounds
        val safeLeft = left.coerceAtLeast(0)
        val safeTop = top.coerceAtLeast(0)
        val safeRight = right.coerceAtMost(bitmap.width)
        val safeBottom = bottom.coerceAtMost(bitmap.height)

        return Bitmap.createBitmap(
            bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop
        )
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

    private fun recognizeTextFromBitmap(bitmap: Bitmap, index: Int, faceID: Boolean) {

        val frame = MLFrame.fromBitmap(bitmap)
        val task = analyzer.asyncAnalyseFrame(frame)

        task.addOnSuccessListener { result ->
            when (index) {
                1 -> if (faceID) binding.resultFamilyName.setText(
                    result.stringValue.replace(
                        "\n",
                        ""
                    )
                )
                else binding.resultIDNumber.setText(result.stringValue.replace("\n", ""))

                2 -> if (faceID) binding.resultName.setText(result.stringValue.replace("\n", ""))
                else binding.resultBirthPlace.setText(result.stringValue.replace("\n", ""))

                3 -> if (faceID) binding.resultSurname.setText(result.stringValue.replace("\n", ""))
                else binding.resultIssued.setText(result.stringValue.replace("\n", ""))

                4 -> if (faceID) binding.resultBirthDate.setText(
                    result.stringValue.replace(
                        "\n",
                        ""
                    )
                )
                else binding.resultDocDate.setText(result.stringValue.replace("\n", ""))
            }
        }.addOnFailureListener { e ->
            handleTextRecognitionError(e)
        }
    }

    private fun recognizeTextFromImage(uri: Uri) {
        val image = InputImage.fromFilePath(this, uri)
        val frame = MLFrame.fromBitmap(image.bitmapInternal)

        val task = analyzer.asyncAnalyseFrame(frame)
        task.addOnSuccessListener {
            val stringBuilder = StringBuilder()
            for (block in task.result.blocks) {
                for (line in block.stringValue) {
                    stringBuilder.append(line).append("\n")
                }
                stringBuilder.append("\n")
            }
        }.addOnFailureListener { e ->
            handleTextRecognitionError(e)
        }
    }


    private fun initializeImagePickerLauncher() {
        imagePickerLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                handleImagePickerResult(uri)
            }
    }

    private fun launchShare() {
        val params = mapOf(
            "surname" to binding.resultFamilyName.text.toString(),
            "name" to binding.resultName.text.toString(),
            "patronymic" to binding.resultSurname.text.toString(),
            "birth_date" to binding.resultBirthDate.text.toString(),
            "sex" to findViewById<RadioButton>(binding.rgSex.checkedRadioButtonId).text.toString(),
            "doc_type" to "УДЛ_Казахстан",
            "doc_series" to "",
            "doc_numb" to binding.resultIDNumber.text.toString(),
            "doc_date" to binding.resultDocDate.text.toString(),
            "issued_by" to binding.resultIssued.text.toString(),
            "olimp_id" to binding.etEventID.text.toString(),
            "grade" to findViewById<RadioButton>(binding.rgGrade.checkedRadioButtonId).text.toString(),
        )
        sendPostRequest(params)
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

    private fun launchImagePicker() {
        imagePickerLauncher.launch("image/*")
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "JPEG_${timeStamp}_", ".jpg", storageDir
        ).apply {
            imageUri = Uri.fromFile(this)
        }
    }

    private fun handleImagePickerResult(uri: Uri?) {
        uri ?: return showToast("Image selection failed. Please try again.")
        imageUri = uri
        binding.imageViewId.setImageURI(uri)
        recognizeTextFromImage(uri)
    }


    // Maneja errores en el reconocimiento de texto
    private fun handleTextRecognitionError(e: Exception) {
        showToast("Ошибка распознавания текста: ${e.localizedMessage}")
        Log.e("TextRecognition", "Ошибка распознавания", e)
        e.printStackTrace()
    }


    // Muestra un mensaje Toast
    private fun showToast(message: String) {
        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
    }

    private fun sendPostRequest(parameters: Map<String, String>) {
        // 1. Создайте клиент OkHttp
        val client = OkHttpClient()

        // 2. Сформируйте тело запроса
        val formBody = FormBody.Builder().apply {
            parameters.forEach { (key, value) -> add(key, value) }
        }.build()

        // 3. Создайте сам запрос
        val request = Request.Builder().url(url).post(formBody).build()

        // 4. Выполните запрос
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Обработка ошибки
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT)
                        .show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                // Чтение ответа
                response.use {
                    if (!response.isSuccessful) {
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Ошибка сервера: ${response.code}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return
                    }
                    val responseBody = response.body?.string() ?: "Пустой ответ"
                    runOnUiThread {
                        val gson = Gson()
                        val serverResponse = gson.fromJson(responseBody, ServerResponse::class.java)
                        Toast.makeText(
                            this@MainActivity, serverResponse.message, Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 101
    }
}