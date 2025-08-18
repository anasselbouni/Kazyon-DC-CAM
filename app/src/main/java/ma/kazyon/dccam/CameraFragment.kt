package ma.kazyon.dccam

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.text.InputFilter
import android.text.Spanned
import android.util.Log
import android.view.LayoutInflater
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import android.text.Editable
import androidx.lifecycle.lifecycleScope
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONObject
import java.io.IOException

class CameraFragment : Fragment() {
    // --- UI Components ---
    private lateinit var viewFinder: PreviewView
    private lateinit var imageCaptureButton: Button
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingTextView: TextView
    private lateinit var statusTextView: TextView // New TextView for status
    private lateinit var magasinNameEditText: EditText
    private lateinit var referenceNameEditText: EditText
    private lateinit var inputLayout: LinearLayout
    private lateinit var capturedImageView: ImageView
    private lateinit var sendImageButton: Button
    private lateinit var retakeImageButton: Button
    private lateinit var cameraButtonsLayout: LinearLayout
    private lateinit var reviewButtonsLayout: LinearLayout

    // --- Camera ---
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    // --- State variables ---
    private var magasinName: String = ""
    private var referenceName: String = ""
    private var capturedBitmap: Bitmap? = null

    // --- API Key storage ---
    private lateinit var sharedPreferences: SharedPreferences
    private var deviceApiKey: String? = null

    companion object {
        private const val TAG = "CameraXApp"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        // --- API Endpoints ---
        private const val HOST = "192.168.100.136" // if changed, please update network_security_config.xml and server as well
        private const val PORT = 6868 // if changed, please update port in server
        private const val CHECK_STATUS = "http://$HOST:$PORT/check_status"
        private const val API_ENDPOINT = "http://$HOST:$PORT/upload_image"
        private const val REGISTER_ENDPOINT = "http://$HOST:$PORT/register_key"

        // --- SharedPreferences keys ---
        private const val PREFS_FILE = "dccam_prefs"
        private const val API_KEY_PREFS = "api_key"

        // --- Fragment args ---
        private const val ARG_MAGASIN_NAME = "magasin_name"
        private const val ARG_REFERENCE_NAME = "reference_name"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize SharedPreferences
        sharedPreferences = requireActivity().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

        // --- Bind UI elements ---
        viewFinder = view.findViewById(R.id.viewFinder)
        imageCaptureButton = view.findViewById(R.id.imageCaptureButton)
        loadingOverlay = view.findViewById(R.id.loadingOverlay)
        loadingTextView = view.findViewById(R.id.loadingTextView)
        statusTextView = view.findViewById(R.id.statusTextView) // Find the new TextView

        magasinNameEditText = view.findViewById(R.id.magasinNameEditText)
        referenceNameEditText = view.findViewById(R.id.referenceNameEditText)
        inputLayout = view.findViewById(R.id.inputLayout)

        capturedImageView = view.findViewById(R.id.capturedImageView)
        sendImageButton = view.findViewById(R.id.sendImageButton)
        retakeImageButton = view.findViewById(R.id.retakeImageButton)

        cameraButtonsLayout = view.findViewById(R.id.camera_buttons_layout)
        reviewButtonsLayout = view.findViewById(R.id.review_buttons_layout)

        // --- Input validation setup ---
        referenceNameEditText.filters = arrayOf(ReferenceInputFilter())

        // --- Auto-format number input (4 characters long) ---
        magasinNameEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                magasinNameEditText.removeTextChangedListener(this)
                val currentText = s.toString()
                val digitsOnly = currentText.filter { it.isDigit() }
                var newText = digitsOnly
                var cursorPosition = magasinNameEditText.selectionStart

                if (newText.length > 4) {
                    newText = newText.substring(0, 4)
                    cursorPosition = 4
                }

                if (currentText != newText) {
                    magasinNameEditText.setText(newText)
                }

                if (cursorPosition > newText.length) {
                    cursorPosition = newText.length
                }

                magasinNameEditText.setSelection(cursorPosition)
                magasinNameEditText.addTextChangedListener(this)
            }
        })

        // Optional: You can remove this part since the input should be empty initially.
        // However, if you want to ensure it's empty on load, you can keep it.
        if (magasinNameEditText.text.toString().isNotEmpty()) {
            magasinNameEditText.setText("")
            magasinNameEditText.setSelection(0)
        }

        // --- Select all text when focus gained ---
//        magasinNameEditText.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
//            if (hasFocus) {
//                (v as? EditText)?.setSelection(1, v.text.length)
//            }
//        }

        referenceNameEditText.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                (v as? EditText)?.selectAll()
            }
        }

        // --- Button listeners ---
        imageCaptureButton.setOnClickListener { takePhoto() }

        sendImageButton.setOnClickListener {
            sendImageButton.isEnabled = false
            retakeImageButton.isEnabled = false
            loadingOverlay.visibility = View.VISIBLE
            loadingTextView.text = getString(R.string.uploading_photo_message)

            capturedBitmap?.let { bitmap ->
                val finalPhotoName = "${magasinName}_${referenceName}"
                uploadImageToApi(bitmap, finalPhotoName)
            } ?: run {
                Toast.makeText(requireContext(), getString(R.string.no_image_to_send), Toast.LENGTH_SHORT).show()
                resetToCameraState()
            }
        }

        retakeImageButton.setOnClickListener {
            resetToCameraState()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // --- Load args if provided ---
        arguments?.let {
            val passedMagasinName = it.getString(ARG_MAGASIN_NAME)
            val passedReferenceName = it.getString(ARG_REFERENCE_NAME)

            if (!passedMagasinName.isNullOrEmpty()) {
                magasinNameEditText.setText(passedMagasinName)
                magasinName = passedMagasinName
            }
            if (!passedReferenceName.isNullOrEmpty()) {
                referenceNameEditText.setText(passedReferenceName)
                referenceName = passedReferenceName
            }
        }

        // --- Permissions & Camera start ---
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // --- API Key management ---
        deviceApiKey = sharedPreferences.getString(API_KEY_PREFS, null)

        if (deviceApiKey == null) {
            deviceApiKey = UUID.randomUUID().toString()
            sharedPreferences.edit().putString(API_KEY_PREFS, deviceApiKey).apply()
            registerDevice()
        } else {
            lifecycleScope.launch {
                checkStatus(deviceApiKey!!)
            }
        }
    }

    // --- Check API key status with server ---
    private suspend fun checkStatus(deviceApiKey1: String) {
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(CHECK_STATUS)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("X-API-Key", deviceApiKey1)
                connection.setRequestProperty("Content-Length", "0")

                val responseCode = connection.responseCode

                withContext(Dispatchers.Main) {
                    when (responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            statusTextView.text = getString(R.string.status_approved)
                            showCameraPreviewState()
                        }
                        HttpURLConnection.HTTP_UNAUTHORIZED -> {
                            statusTextView.text = getString(R.string.status_not_approved)
                        }
                        else -> {
                            statusTextView.text = getString(R.string.status_error, responseCode)
                        }
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    statusTextView.text = getString(R.string.status_connection_error)
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    // --- Register device on backend with generated API key ---
    private fun registerDevice() {
        showCameraPreviewState()
        loadingOverlay.visibility = View.VISIBLE
        loadingTextView.text = getString(R.string.registering_device_message)
        statusTextView.text = getString(R.string.status_awaiting_approval)
        imageCaptureButton.isEnabled = false

        val newGeneratedKey = UUID.randomUUID().toString()

        CoroutineScope(Dispatchers.IO).launch {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(REGISTER_ENDPOINT)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")

                val jsonPayload = JSONObject().apply {
                    put("key", newGeneratedKey)
                    put("device_name", android.os.Build.MODEL)
                }.toString()

                connection.outputStream.use { os ->
                    os.write(jsonPayload.toByteArray())
                }

                val responseCode = connection.responseCode

                withContext(Dispatchers.Main) {
                    loadingOverlay.visibility = View.GONE

                    when (responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            sharedPreferences.edit().putString(API_KEY_PREFS, newGeneratedKey).apply()
                            deviceApiKey = newGeneratedKey
                            statusTextView.text = getString(R.string.status_approved)
                            showCameraPreviewState()
                            imageCaptureButton.isEnabled = true
                            checkStatus(deviceApiKey!!)
                        }
                        HttpURLConnection.HTTP_ACCEPTED -> {
                            sharedPreferences.edit().putString(API_KEY_PREFS, newGeneratedKey).apply()
                            deviceApiKey = newGeneratedKey
                            Toast.makeText(requireContext(), getString(R.string.device_registered_waiting), Toast.LENGTH_LONG).show()
                            statusTextView.text = getString(R.string.status_awaiting_approval)
                            showCameraPreviewState()
                            loadingTextView.text = getString(R.string.waiting_for_approval_message)
                            imageCaptureButton.isEnabled = true
                        }

                        else -> {
                            statusTextView.text = getString(R.string.status_registration_error)
                            showErrorDialog(getString(R.string.registration_failed_title), getString(R.string.server_response_error, responseCode))
                            deviceApiKey = null
                            imageCaptureButton.isEnabled = true
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    statusTextView.text = getString(R.string.status_connection_error)
                    showErrorDialog(getString(R.string.connection_error_title), getString(R.string.registration_connection_failed, e.message))
                    deviceApiKey = null
                    loadingOverlay.visibility = View.GONE
                    imageCaptureButton.isEnabled = false
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    // --- Setup CameraX preview and capture ---
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // --- Capture photo, validate input, show preview for review ---
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val currentMagasin = magasinNameEditText.text.toString().trim()
        val currentReference = referenceNameEditText.text.toString().trim()
        lifecycleScope.launch {
            checkStatus(deviceApiKey!!)
        }
        if (!isValidInput(currentMagasin, currentReference)) {
            return
        }

        magasinName = currentMagasin
        referenceName = currentReference
        magasinNameEditText.isEnabled = false
        referenceNameEditText.isEnabled = false
        imageCaptureButton.isEnabled = false
        loadingOverlay.visibility = View.VISIBLE
        loadingTextView.text = getString(R.string.capturing_image_message)

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(requireContext(), getString(R.string.photo_capture_failed, exc.message), Toast.LENGTH_SHORT).show()
                        resetToCameraState()
                        loadingOverlay.visibility = View.GONE
                    }
                }

                override fun onCaptureSuccess(imageProxy: androidx.camera.core.ImageProxy) {
                    capturedBitmap?.recycle()
                    capturedBitmap = null
                    val originalBitmap = imageProxy.toBitmap()
                    imageProxy.close()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val rotatedAndOrientedBitmap = rotateBitmapIfNecessary(originalBitmap, imageProxy.imageInfo.rotationDegrees)
                            val bitmapWithDateTime = addDateTimeToBitmap(rotatedAndOrientedBitmap)
                            capturedBitmap = bitmapWithDateTime
                            withContext(Dispatchers.Main) {
                                capturedImageView.setImageBitmap(capturedBitmap)
                                showImageReviewState()
                            }
                            if (originalBitmap != rotatedAndOrientedBitmap) {
                                originalBitmap.recycle()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing image for display", e)
                            withContext(Dispatchers.Main) {
                                showErrorDialog(getString(R.string.image_processing_error), getString(R.string.failed_to_process_or_upload_image, e.message))
                                resetToCameraState()
                            }
                        } finally {
                            withContext(Dispatchers.Main) {
                                loadingOverlay.visibility = View.GONE
                            }
                        }
                    }
                }
            })
    }

    // --- Upload image to backend with headers (name + API key) ---
    private fun uploadImageToApi(bitmap: Bitmap, photoNameWithTimestamp: String) {
        CoroutineScope(Dispatchers.IO).launch {
            var connection: HttpURLConnection? = null
            var outputStream: OutputStream? = null
            try {
                val url = URL(API_ENDPOINT)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.setRequestProperty("X-Photo-Name", "$photoNameWithTimestamp.jpg")
                connection.setRequestProperty("X-API-Key", deviceApiKey)

                val byteArrayOutputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream)
                val imageBytes = byteArrayOutputStream.toByteArray()

                outputStream = connection.outputStream
                outputStream.write(imageBytes)
                outputStream.flush()

                val responseCode = connection.responseCode
                val responseMessage = connection.responseMessage

                withContext(Dispatchers.Main) {
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        Toast.makeText(requireContext(), getString(R.string.upload_success_message, photoNameWithTimestamp), Toast.LENGTH_SHORT).show()
                        resetToCameraState()
                    } else {
                        val errorMessage = when (responseCode) {
                            HttpURLConnection.HTTP_UNAUTHORIZED -> {
                                statusTextView.text = getString(R.string.status_not_approved)
                                getString(R.string.device_not_authorized)
                            }
                            else -> getString(R.string.upload_failed_message, responseCode, responseMessage)
                        }
                        showErrorDialog(getString(R.string.upload_error), errorMessage)
                        sendImageButton.isEnabled = true
                        retakeImageButton.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    showErrorDialog(getString(R.string.connection_error), getString(R.string.error_uploading_photo, e.message))
                    sendImageButton.isEnabled = true
                    retakeImageButton.isEnabled = true
                }
            } finally {
                outputStream?.close()
                connection?.disconnect()
                withContext(Dispatchers.Main) {
                    loadingOverlay.visibility = View.GONE
                }
            }
        }
    }

    // --- Permissions helper ---
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult (requestCode: Int, permissions: Array<String>, grantResults: IntArray,) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.permissions_not_granted_toast),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }


    // --- UI State management ---
    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok_button)) { dialog: DialogInterface, _: Int ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showCameraPreviewState() {
        viewFinder.visibility = View.VISIBLE
        inputLayout.visibility = View.VISIBLE
        cameraButtonsLayout.visibility = View.VISIBLE

        capturedImageView.visibility = View.GONE
        reviewButtonsLayout.visibility = View.GONE

        magasinNameEditText.isEnabled = true
        referenceNameEditText.isEnabled = true
        imageCaptureButton.isEnabled = true
    }

    private fun showImageReviewState() {
        viewFinder.visibility = View.GONE
        inputLayout.visibility = View.GONE
        cameraButtonsLayout.visibility = View.GONE

        capturedImageView.visibility = View.VISIBLE
        reviewButtonsLayout.visibility = View.VISIBLE

        sendImageButton.isEnabled = true
        retakeImageButton.isEnabled = true
    }

    private fun resetToCameraState() {
        capturedBitmap?.recycle()
        capturedBitmap = null
        capturedImageView.setImageDrawable(null)

        showCameraPreviewState()
        loadingOverlay.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        capturedBitmap?.recycle()
        capturedBitmap = null
    }

    // --- Image utilities ---
    private fun rotateBitmapIfNecessary(originalBitmap: Bitmap, imageProxyRotationDegrees: Int): Bitmap {
        val rotationDegrees = imageProxyRotationDegrees
        if (rotationDegrees == 0) {
            return originalBitmap
        }
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        val rotatedBitmap = Bitmap.createBitmap(
            originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true
        )
        if (originalBitmap != rotatedBitmap) {
            originalBitmap.recycle()
        }
        return rotatedBitmap
    }

    private fun addDateTimeToBitmap(originalBitmap: Bitmap): Bitmap {
        val mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 50f
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val currentDateTime = dateFormat.format(Date())
        val textBounds = android.graphics.Rect()
        paint.getTextBounds(currentDateTime, 0, currentDateTime.length, textBounds)
        val padding = 20f
        val x = padding
        val y = mutableBitmap.height - padding - textBounds.height()
        canvas.drawText(currentDateTime, x, y, paint)
        return mutableBitmap
    }

    // --- Input validation ---
    private fun isValidInput(magasin: String, reference: String): Boolean {
        if (!magasin.matches(Regex("(?i)\\d{4}"))) {
            Toast.makeText(requireContext(), getString(R.string.error_magasin_format), Toast.LENGTH_SHORT).show()
            magasinNameEditText.requestFocus()
            return false
        }
        if (!reference.matches(Regex("5\\d{9}"))) {
            Toast.makeText(requireContext(), getString(R.string.error_reference_format), Toast.LENGTH_SHORT).show()
            referenceNameEditText.requestFocus()
            return false
        }
        return true
    }

    class ReferenceInputFilter : InputFilter {
        override fun filter(
            source: CharSequence?, start: Int, end: Int, dest: Spanned?, dstart: Int, dend: Int
        ): CharSequence? {
            val currentLength = dest?.length ?: 0
            val replacementLength = end - start
            val newLength = currentLength - (dend - dstart) + replacementLength
            if (replacementLength == 0 && newLength < currentLength) {
                return null
            }
            if (newLength > 10) {
                return ""
            }
            for (i in start until end) {
                val char = source?.get(i) ?: return ""
                when (dstart + (i - start)) {
                    0 -> {
                        if (char != '5') {
                            return ""
                        }
                    }
                    in 1..9 -> {
                        if (!char.isDigit()) {
                            return ""
                        }
                    }
                    else -> {
                        return ""
                    }
                }
            }
            return null
        }
    }
}