package ma.kazyon.dccam

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.text.InputFilter // Import this
import android.text.Spanned // Import this
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
import android.widget.ProgressBar
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
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment() {

    private lateinit var viewFinder: PreviewView
    private lateinit var imageCaptureButton: Button

    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingTextView: TextView

    private lateinit var magasinNameEditText: EditText
    private lateinit var referenceNameEditText: EditText
    private lateinit var inputLayout: LinearLayout

    private lateinit var capturedImageView: ImageView
    private lateinit var sendImageButton: Button
    private lateinit var retakeImageButton: Button

    private lateinit var cameraButtonsLayout: LinearLayout
    private lateinit var reviewButtonsLayout: LinearLayout

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private var magasinName: String = ""
    private var referenceName: String = ""

    private var capturedBitmap: Bitmap? = null

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        private const val API_ENDPOINT = "http://192.168.100.5:6767/upload_image"
        private const val API_KEY = "kZ3pYx9qL2oN7uV5wT8rF4sD1jH6gC0bEaI"

        private const val ARG_MAGASIN_NAME = "magasin_name"
        private const val ARG_REFERENCE_NAME = "reference_name"

        fun newInstance(magasinName: String, referenceName: String): CameraFragment {
            return CameraFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MAGASIN_NAME, magasinName)
                    putString(ARG_REFERENCE_NAME, referenceName)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewFinder = view.findViewById(R.id.viewFinder)
        imageCaptureButton = view.findViewById(R.id.imageCaptureButton)

        loadingOverlay = view.findViewById(R.id.loadingOverlay)
        loadingTextView = view.findViewById(R.id.loadingTextView)

        magasinNameEditText = view.findViewById(R.id.magasinNameEditText)
        referenceNameEditText = view.findViewById(R.id.referenceNameEditText)
        inputLayout = view.findViewById(R.id.inputLayout)

        capturedImageView = view.findViewById(R.id.capturedImageView)
        sendImageButton = view.findViewById(R.id.sendImageButton)
        retakeImageButton = view.findViewById(R.id.retakeImageButton)

        cameraButtonsLayout = view.findViewById(R.id.camera_buttons_layout)
        reviewButtonsLayout = view.findViewById(R.id.review_buttons_layout)

        // --- Apply custom InputFilters and AllCaps ---
        // We will replace the MagasinInputFilter with a TextWatcher for better control
        referenceNameEditText.filters = arrayOf(ReferenceInputFilter())

        // --- TEXTWATCHER IMPLEMENTATION FOR MAGASIN NAME ---
        magasinNameEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // No action needed here
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // No action needed here
            }

            override fun afterTextChanged(s: Editable?) {
                // Temporarily remove the listener to avoid infinite loops
                magasinNameEditText.removeTextChangedListener(this)

                val currentText = s.toString()
                val prefix = "M"
                var cursorPosition = magasinNameEditText.selectionStart

                // Case 1: The text is empty or doesn't start with 'M' (case-insensitive)
                if (currentText.isEmpty() || !currentText.startsWith(prefix, ignoreCase = true)) {
                    val newText = prefix
                    magasinNameEditText.setText(newText)
                    magasinNameEditText.setSelection(newText.length)
                } else {
                    // Case 2: Ensure the rest of the characters are digits
                    // We only check from the second character onwards
                    val digitsOnly = currentText.substring(1).filter { it.isDigit() }
                    val newText = prefix + digitsOnly

                    if (currentText != newText) {
                        magasinNameEditText.setText(newText)
                        // Adjust cursor position to be at the end of the valid text
                        cursorPosition = newText.length
                    }

                    // Case 3: Enforce max length of 4 (M + 3 digits)
                    if (magasinNameEditText.text.length > 4) {
                        val truncatedText = magasinNameEditText.text.toString().substring(0, 4)
                        magasinNameEditText.setText(truncatedText)
                        cursorPosition = 4 // Set cursor to the end
                    } else if (magasinNameEditText.text.length < 1) {
                        // This handles a very rare case where the text might become empty
                        magasinNameEditText.setText(prefix)
                        cursorPosition = 1
                    }
                }

                // Restore the cursor position
                if (cursorPosition > magasinNameEditText.text.length) {
                    cursorPosition = magasinNameEditText.text.length
                }
                magasinNameEditText.setSelection(cursorPosition)

                // Re-add the listener
                magasinNameEditText.addTextChangedListener(this)
            }
        })

        // Set the initial text to "M" if it's empty on creation
        if (magasinNameEditText.text.toString().isEmpty()) {
            magasinNameEditText.setText("M")
            magasinNameEditText.setSelection(1) // Place cursor after the 'M'
        }
        // --- END OF TEXTWATCHER IMPLEMENTATION ---


        // Select all text on focus for Magasin input
        magasinNameEditText.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                (v as? EditText)?.setSelection(1, v.text.length)
            }
        }

        // Select all text on focus for Reference input
        referenceNameEditText.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                (v as? EditText)?.selectAll()
            }
        }

        imageCaptureButton.setOnClickListener { takePhoto() }

        sendImageButton.setOnClickListener {
            sendImageButton.isEnabled = false
            retakeImageButton.isEnabled = false
            loadingOverlay.visibility = View.VISIBLE
            loadingTextView.text = getString(R.string.uploading_photo_message)

            capturedBitmap?.let { bitmap ->
                val timestamp = SimpleDateFormat("ddMMyyyy_HHmm", Locale.getDefault()).format(Date())
                val finalPhotoName = "${magasinName}_${referenceName}_$timestamp"
                uploadImageToApi(bitmap, finalPhotoName)
            } ?: run {
                Toast.makeText(requireContext(), "No image to send.", Toast.LENGTH_SHORT).show()
                resetToCameraState()
            }
        }

        retakeImageButton.setOnClickListener {
            resetToCameraState()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

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

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        showCameraPreviewState()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val currentMagasin = magasinNameEditText.text.toString().trim()
        val currentReference = referenceNameEditText.text.toString().trim()

        // Keep the isValidInput for final validation on button press,
        // as InputFilter only handles character-by-character and not full string compliance
        // (e.g., if user types 'M' then deletes it and types numbers, InputFilter alone won't catch it)
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
                connection.setRequestProperty("X-API-Key", API_KEY)

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
                            HttpURLConnection.HTTP_UNAUTHORIZED -> getString(R.string.upload_unauthorized_error)
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

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult (
        requestCode: Int, permissions: Array<String>, grantResults: IntArray,
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog: DialogInterface, _: Int ->
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

    private fun rotateBitmapIfNecessary(originalBitmap: Bitmap, imageProxyRotationDegrees: Int): Bitmap {
        val rotationDegrees = imageProxyRotationDegrees

        if (rotationDegrees == 0) {
            return originalBitmap
        }

        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())

        val rotatedBitmap = Bitmap.createBitmap(
            originalBitmap,
            0,
            0,
            originalBitmap.width,
            originalBitmap.height,
            matrix,
            true
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

    // This isValidInput is primarily for the final check when the capture button is pressed.
    // The InputFilters handle real-time blocking of invalid characters.
    private fun isValidInput(magasin: String, reference: String): Boolean {
        if (!magasin.matches(Regex("(?i)M\\d{3}"))) {
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

    /**
     * Custom InputFilter for Magasin EditText.
     * Allows 'M' (case-insensitive) as the first char, then 3 digits.
     */
    class MagasinInputFilter : InputFilter {
        override fun filter(
            source: CharSequence?,
            start: Int,
            end: Int,
            dest: Spanned?,
            dstart: Int, // Where the new text will be inserted in dest
            dend: Int    // Where the new text will end in dest
        ): CharSequence? {
            // Calculate the length of the string after the proposed change
            val currentLength = dest?.length ?: 0
            val replacementLength = end - start
            val newLength = currentLength - (dend - dstart) + replacementLength

            // If deleting characters, allow it (but ensure max length is still enforced by XML)
            if (replacementLength == 0 && newLength < currentLength) {
                return null // Allow deletion
            }

            // Only allow input up to max length (4 for Magasin). This is also handled by XML maxLength.
            if (newLength > 4) {
                return "" // Reject input if it exceeds max length
            }

            // Iterate over each character being input by the user
            for (i in start until end) {
                val char = source?.get(i) ?: return "" // Should not be null

                when (dstart + (i - start)) { // Calculate the absolute position of the character
                    0 -> { // First character
                        if (char.toString().lowercase() != "m") {
                            return "" // Reject if not 'M'
                        }
                    }
                    1, 2, 3 -> { // Subsequent 3 characters
                        if (!char.isDigit()) {
                            return "" // Reject if not a digit
                        }
                    }
                    else -> {
                        // This case should ideally not be reached if maxLength is 4
                        // But as a safeguard, reject anything beyond the 4th char.
                        return ""
                    }
                }
            }

            // If all characters in source are valid, return null to accept them
            return null
        }
    }

    /**
     * Custom InputFilter for Reference EditText.
     * Allows '5' as the first char, then 9 digits.
     */
    class ReferenceInputFilter : InputFilter {
        override fun filter(
            source: CharSequence?,
            start: Int,
            end: Int,
            dest: Spanned?,
            dstart: Int, // Where the new text will be inserted in dest
            dend: Int    // Where the new text will end in dest
        ): CharSequence? {
            // Calculate the length of the string after the proposed change
            val currentLength = dest?.length ?: 0
            val replacementLength = end - start
            val newLength = currentLength - (dend - dstart) + replacementLength

            // If deleting characters, allow it (but ensure max length is still enforced by XML)
            if (replacementLength == 0 && newLength < currentLength) {
                return null // Allow deletion
            }

            // Only allow input up to max length (10 for Reference). This is also handled by XML maxLength.
            if (newLength > 10) {
                return "" // Reject input if it exceeds max length
            }

            // Iterate over each character being input by the user
            for (i in start until end) {
                val char = source?.get(i) ?: return "" // Should not be null

                when (dstart + (i - start)) { // Calculate the absolute position of the character
                    0 -> { // First character
                        if (char != '5') {
                            return "" // Reject if not '5'
                        }
                    }
                    in 1..9 -> { // Subsequent 9 characters (from index 1 to 9)
                        if (!char.isDigit()) {
                            return "" // Reject if not a digit
                        }
                    }
                    else -> {
                        // This case should ideally not be reached if maxLength is 10
                        return ""
                    }
                }
            }

            // If all characters in source are valid, return null to accept them
            return null
        }
    }
}