package ma.kazyon.dccam

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import ma.kazyon.dccam.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check for a saved language preference. If found, launch the camera activity directly.
        val sharedPrefs = getSharedPreferences("language_prefs", Context.MODE_PRIVATE)
        val savedLanguage = sharedPrefs.getString("app_language", null)

        if (savedLanguage != null) {
            setLocale(savedLanguage)
            startCameraActivity()
            return
        }

        // If no language is saved, set up the buttons for language selection.
        binding.englishButton.setOnClickListener {
            saveAndSetLanguage("en")
        }

        binding.frenchButton.setOnClickListener {
            saveAndSetLanguage("fr")
        }

        binding.arabicButton.setOnClickListener {
            saveAndSetLanguage("ar")
        }
    }

    // Save the selected language to SharedPreferences and apply the change
    private fun saveAndSetLanguage(languageCode: String) {
        val sharedPrefs = getSharedPreferences("language_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("app_language", languageCode).apply()
        setLocale(languageCode)
        startCameraActivity()
    }

    // This function changes the app's locale and updates the configuration
    private fun setLocale(languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration()
        config.setLocale(locale)
        baseContext.resources.updateConfiguration(config, baseContext.resources.displayMetrics)
    }

    // Starts the new activity that hosts the CameraFragment
    private fun startCameraActivity() {
        val intent = Intent(this, CameraActivity::class.java)
        startActivity(intent)
        finish() // Close MainActivity so the user can't navigate back to it
    }
}