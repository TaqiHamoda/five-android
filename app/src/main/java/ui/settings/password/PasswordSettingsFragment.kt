package ui.settings.password

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import org.blokada.R // Corrected R import
import service.PasswordManager

class PasswordSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(org.blokada.R.xml.settings_password, rootKey) // Use org.blokada.R

        val passwordPreference: EditTextPreference? = findPreference("app_password")
        val enablePasswordPreference: SwitchPreferenceCompat? = findPreference("app_password_enabled")

        // Initialize summary for password preference
        passwordPreference?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()

        // Set initial state of enable switch
        enablePasswordPreference?.isChecked = PasswordManager.hasPassword()

        passwordPreference?.setOnPreferenceChangeListener { preference, newValue ->
            val newPassword = newValue as String
            if (newPassword.isEmpty()) {
                PasswordManager.clearPassword()
                enablePasswordPreference?.isChecked = false
            } else {
                PasswordManager.setPassword(newPassword)
                enablePasswordPreference?.isChecked = true
            }
            true // Return true to save the new value
        }

        enablePasswordPreference?.setOnPreferenceChangeListener { preference, newValue ->
            val isEnabled = newValue as Boolean
            if (isEnabled) {
                // If enabling, and no password is set, prompt to set one
                if (!PasswordManager.hasPassword()) {
                    passwordPreference?.callChangeListener("") // Trigger password setting dialog
                    return@setOnPreferenceChangeListener false // Don't update switch yet
                }
            } else {
                // If disabling, clear the password
                PasswordManager.clearPassword()
            }
            true
        }
    }
}