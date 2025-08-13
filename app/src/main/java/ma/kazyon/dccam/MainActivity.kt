package ma.kazyon.dccam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.InputFilter
import android.text.Spanned
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout // <--- ADD THIS LINE
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var cameraAndInputLayout: LinearLayout
    private lateinit var magasinEditText: EditText
    private lateinit var referenceEditText: EditText
    private lateinit var startCameraSessionButton: Button

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraAndInputLayout = findViewById(R.id.camera_input_layout)
        magasinEditText = findViewById(R.id.magasin_edit_text)
        referenceEditText = findViewById(R.id.reference_edit_text)
        startCameraSessionButton = findViewById(R.id.start_camera_session_button)

        // --- REPLACE THE CUSTOM INPUT FILTER WITH THE TEXTWATCHER ---
        // magasinEditText.filters = arrayOf(CustomInputFilter(magasinEditText), InputFilter.LengthFilter(4))

        // Set the InputFilter for the referenceEditText only
        // Note: The CustomInputFilter has a bug, it's better to use a dedicated filter or TextWatcher for it.
        // For simplicity and correctness, let's keep the filter for reference for now.
        referenceEditText.filters = arrayOf(CustomInputFilter(referenceEditText), InputFilter.LengthFilter(10))

        // --- TEXTWATCHER IMPLEMENTATION FOR MAGASIN NAME ---
        magasinEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // Not needed for this logic
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Not needed for this logic
            }

            override fun afterTextChanged(s: Editable?) {
                magasinEditText.removeTextChangedListener(this)

                val currentText = s.toString()
                val prefix = "M"
                var cursorPosition = magasinEditText.selectionStart

                // Case 1: The text is empty or doesn't start with 'M' (case-insensitive)
                if (currentText.isEmpty() || !currentText.startsWith(prefix, ignoreCase = true)) {
                    val newText = prefix
                    magasinEditText.setText(newText)
                    magasinEditText.setSelection(newText.length)
                } else {
                    // Case 2: Ensure the rest of the characters are digits
                    val digitsOnly = currentText.substring(1).filter { it.isDigit() }
                    val newText = prefix + digitsOnly

                    if (currentText != newText) {
                        magasinEditText.setText(newText)
                        cursorPosition = newText.length
                    }

                    // Case 3: Enforce max length of 4 (M + 3 digits)
                    if (magasinEditText.text.length > 4) {
                        val truncatedText = magasinEditText.text.toString().substring(0, 4)
                        magasinEditText.setText(truncatedText)
                        cursorPosition = 4
                    }
                }

                // Restore the cursor position
                if (cursorPosition > magasinEditText.text.length) {
                    cursorPosition = magasinEditText.text.length
                }
                magasinEditText.setSelection(cursorPosition)

                magasinEditText.addTextChangedListener(this)
            }
        })

        // Set the initial text to "M" if it's empty
        if (magasinEditText.text.toString().isEmpty()) {
            magasinEditText.setText("M")
            magasinEditText.setSelection(1) // Place cursor after the 'M'
        }
        // --- END OF TEXTWATCHER IMPLEMENTATION ---

        // Select only the numbers on focus
        magasinEditText.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                (v as? EditText)?.setSelection(1, v.text.length)
            }
        }

        // Select all text on focus for Reference input
        referenceEditText.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                (v as? EditText)?.selectAll()
            }
        }

        // Ensure input layout is visible initially
        cameraAndInputLayout.visibility = View.VISIBLE

        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                Toast.makeText(this, getString(R.string.permissions_granted), Toast.LENGTH_SHORT).show()
                startCameraSessionButton.isEnabled = true
            } else {
                Toast.makeText(this, getString(R.string.permissions_denied), Toast.LENGTH_LONG).show()
                startCameraSessionButton.isEnabled = false
            }
        }

        startCameraSessionButton.setOnClickListener {
            val magasinName = magasinEditText.text.toString().trim()
            val referenceName = referenceEditText.text.toString().trim()

            if (isValidInput(magasinName, referenceName)) {
                // Step 1: Hide the keyboard first, before hiding the layout
                val view = currentFocus
                if (view != null) {
                    val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
                }

                // Step 2: Hide the input layout after the keyboard is hidden
                cameraAndInputLayout.visibility = View.GONE

                // Step 3: Start CameraFragment
                val fragment = CameraFragment.newInstance(magasinName, referenceName)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .addToBackStack(null)
                    .commit()
                findViewById<FrameLayout>(R.id.fragment_container).visibility = View.VISIBLE
            } else {
                Toast.makeText(this, getString(R.string.input_validation_failed), Toast.LENGTH_LONG).show()
            }
        }

        checkAndRequestPermissions()
    }

    // ... (rest of the MainActivity code remains the same) ...
    // You can also remove the CustomInputFilter class entirely if you decide to use
    // TextWatchers for all your EditTexts, but for now, it's still needed for referenceEditText.
    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    override fun onBackPressed() {
        if (findViewById<FrameLayout>(R.id.fragment_container).visibility == View.VISIBLE && supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            findViewById<FrameLayout>(R.id.fragment_container).visibility = View.GONE
            cameraAndInputLayout.visibility = View.VISIBLE
        } else {
            super.onBackPressed()
        }
    }


    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.INTERNET)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Toast.makeText(this, getString(R.string.permissions_already_granted), Toast.LENGTH_SHORT).show()
            startCameraSessionButton.isEnabled = true
        }
    }

    private fun isValidInput(magasin: String, reference: String): Boolean {
        var isValid = true

        magasinEditText.error = null
        referenceEditText.error = null

        if (magasin.isEmpty()) {
            magasinEditText.error = getString(R.string.error_magasin_empty)
            isValid = false
        } else if (magasin.length != 4) {
            magasinEditText.error = getString(R.string.error_magasin_length)
            isValid = false
        } else if (!magasin.startsWith("M", ignoreCase = true)) {
            magasinEditText.error = getString(R.string.error_magasin_starts_with_M)
            isValid = false
        } else if (!magasin.substring(1).matches(Regex("^[0-9]*$"))) {
            magasinEditText.error = getString(R.string.error_magasin_numbers_after_M)
            isValid = false
        }

        if (reference.isEmpty()) {
            referenceEditText.error = getString(R.string.error_reference_empty)
            isValid = false
        } else if (reference.length != 10) {
            referenceEditText.error = getString(R.string.error_reference_length)
            isValid = false
        } else if (!reference.startsWith("5")) {
            referenceEditText.error = getString(R.string.error_reference_starts_with_5)
            isValid = false
        } else if (!reference.matches(Regex("^[0-9]*$"))) {
            referenceEditText.error = getString(R.string.error_reference_only_numbers)
            isValid = false
        }

        return isValid
    }

    // The CustomInputFilter is still in place for the referenceEditText.
    // It's still functional, but you may consider a TextWatcher for it as well in the future.
    private inner class CustomInputFilter(private val editText: EditText) : InputFilter {
        override fun filter(
            source: CharSequence?,
            start: Int,
            end: Int,
            dest: Spanned?,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            val result = StringBuilder(dest).replace(dstart, dend, source?.subSequence(start, end).toString())
            val currentText = result.toString()

            return if (editText.id == R.id.magasin_edit_text) {
                // This part is now superseded by the TextWatcher, but is left for context.
                // It's recommended to remove this check from here.
                if (currentText.isNotEmpty()) {
                    if (!currentText.startsWith("M", ignoreCase = true)) {
                        return ""
                    }
                    if (currentText.length > 1 && !currentText.substring(1).matches(Regex("^[0-9]*$"))) {
                        return ""
                    }
                }
                null
            } else if (editText.id == R.id.reference_edit_text) {
                if (currentText.isNotEmpty()) {
                    if (!currentText.startsWith("5")) {
                        return ""
                    }
                    source?.forEach { char ->
                        if (!char.isDigit()) {
                            return ""
                        }
                    }
                }
                null
            } else {
                null
            }
        }
    }
}