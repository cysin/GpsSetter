package com.cysindex.telequant.ui


import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat.getSystemService
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import com.cysindex.telequant.R
import com.cysindex.telequant.databinding.SettingsActivityBinding
import com.cysindex.telequant.utils.JoystickService
import com.cysindex.telequant.utils.PrefManager
import com.cysindex.telequant.utils.ext.showToast
import androidx.appcompat.app.AppCompatActivity
import com.highcapable.yukihookapi.hook.xposed.prefs.ui.ModulePreferenceFragment
import rikka.preference.SimpleMenuPreference


class SettingsActivity : AppCompatActivity() {



    private val binding by lazy {
        SettingsActivityBinding.inflate(layoutInflater)
    }

    class SettingPreferenceDataStore() : PreferenceDataStore() {
        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return when (key) {
                "isJoyStickEnable" -> PrefManager.isJoyStickEnable
                "gcj02_output" -> PrefManager.gcj02Output
                "spoof_cell" -> PrefManager.spoofCell
                "spoof_wifi" -> PrefManager.spoofWifi
                "spoof_bluetooth" -> PrefManager.spoofBluetooth
                "spoof_timezone" -> PrefManager.spoofTimeZone
                "tile_proxy_enabled" -> PrefManager.tileProxyEnabled
                "offline_map" -> PrefManager.offlineMap
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }

        override fun putBoolean(key: String?, value: Boolean) {
            return when (key) {
                "isJoyStickEnable" -> PrefManager.isJoyStickEnable = value
                "gcj02_output" -> PrefManager.gcj02Output = value
                "spoof_cell" -> PrefManager.spoofCell = value
                "spoof_wifi" -> PrefManager.spoofWifi = value
                "spoof_bluetooth" -> PrefManager.spoofBluetooth = value
                "spoof_timezone" -> PrefManager.spoofTimeZone = value
                "tile_proxy_enabled" -> PrefManager.tileProxyEnabled = value
                "offline_map" -> PrefManager.offlineMap = value
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }

        override fun getString(key: String?, defValue: String?): String? {
            return when (key) {
                "accuracy_settings" -> PrefManager.accuracy
                "darkTheme" -> PrefManager.darkTheme.toString()
                "jitter_radius" -> PrefManager.jitterRadius
                "jitter_mode" -> PrefManager.jitterMode
                "tile_proxy_host" -> PrefManager.tileProxyHost
                "tile_proxy_port" -> PrefManager.tileProxyPort
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }

        override fun putString(key: String?, value: String?) {
            return when (key) {
                "accuracy_settings" -> PrefManager.accuracy = value
                "darkTheme" -> PrefManager.darkTheme = value!!.toInt()
                "jitter_radius" -> PrefManager.jitterRadius = value
                "jitter_mode" -> PrefManager.jitterMode = value
                "tile_proxy_host" -> PrefManager.tileProxyHost = value
                "tile_proxy_port" -> PrefManager.tileProxyPort = value
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }
    }






    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        theme.applyStyle(rikka.material.preference.R.style.ThemeOverlay_Rikka_Material3_Preference, true);
        setSupportActionBar(binding.toolbar)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsPreferenceFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)


        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            }
        )

    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
        }
        return super.onOptionsItemSelected(item)
    }


    class SettingsPreferenceFragment : ModulePreferenceFragment() {


        override fun onCreatePreferencesInModuleApp(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager?.preferenceDataStore = SettingPreferenceDataStore()
            setPreferencesFromResource(R.xml.setting, rootKey)



            findPreference<EditTextPreference>("accuracy_settings")?.let {
                it.summary = "${PrefManager.accuracy} m."
                it.setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_NUMBER;
                    editText.keyListener = DigitsKeyListener.getInstance("0123456789.,");
                    editText.addTextChangedListener(getCommaReplacerTextWatcher(editText));
                }

                it.setOnPreferenceChangeListener { preference, newValue ->
                    try {
                        newValue as String?
                        preference.summary = "$newValue  m."
                    } catch (n: NumberFormatException) {
                        n.printStackTrace()
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.enter_valid_input),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
            }

            findPreference<SimpleMenuPreference>("darkTheme")?.setOnPreferenceChangeListener { _, newValue ->
                val newMode = (newValue as String).toInt()
                if (PrefManager.darkTheme != newMode) {
                    AppCompatDelegate.setDefaultNightMode(newMode)
                    activity?.recreate()
                }
                true
            }

            findPreference<Preference>("isJoyStickEnable")?.let {
                it.setOnPreferenceClickListener {
                    if (askOverlayPermission()){
                        if (isJoystickRunning()){
                            requireContext().stopService(Intent(context,JoystickService::class.java))
                            it.summary = "Joystick running"
                        }else if (PrefManager.isStarted){
                            requireContext().startService(Intent(context,JoystickService::class.java))
                            it.summary = "Joystick not running"
                        }else {
                            requireContext().showToast(requireContext().getString(R.string.location_not_select))
                        }
                    }
                    true
                }

            }



        }

        private fun isJoystickRunning(): Boolean {
            var isRunning = false
            val manager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager? ?: return false
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if ("com.cysindex.telequant.utils.JoystickService" == service.service.className) {
                    isRunning = true
                }
            }
            return isRunning
        }


        private fun askOverlayPermission() : Boolean {
            if (Settings.canDrawOverlays(context)){
                return true
            }
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context?.applicationContext?.packageName}" ))
            requireContext().startActivity(intent)
            return false
        }


        private fun getCommaReplacerTextWatcher(editText: EditText): TextWatcher {
            return object : TextWatcher {
                override fun beforeTextChanged(
                    charSequence: CharSequence,
                    i: Int,
                    i1: Int,
                    i2: Int
                ) {
                }

                override fun onTextChanged(
                    charSequence: CharSequence,
                    i: Int,
                    i1: Int,
                    i2: Int
                ) {
                }

                override fun afterTextChanged(editable: Editable) {
                    val text = editable.toString()
                    if (text.contains(",")) {
                        editText.setText(text.replace(",", "."))
                        editText.setSelection(editText.text.length)
                    }
                }
            }
        }

    }


}