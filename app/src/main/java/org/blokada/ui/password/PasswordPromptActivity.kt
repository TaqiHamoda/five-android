package org.blokada.ui.password

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import org.blokada.R // Corrected R import
import org.blokada.databinding.ActivityPasswordPromptBinding // Corrected binding import
import service.PasswordManager

class PasswordPromptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPasswordPromptBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screenshots and hide content from recent apps
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        binding = ActivityPasswordPromptBinding.inflate(layoutInflater)
        setContentView(binding.root) // binding.root is correct

        title = getString(org.blokada.R.string.password_prompt_title) // Use org.blokada.R

        // Disable back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing to disable back button
            }
        })

        binding.unlockButton.setOnClickListener {
            val enteredPassword = binding.passwordEditText.text.toString()
            if (PasswordManager.checkPassword(enteredPassword)) {
                setResult(RESULT_OK)
                finish()
            } else {
                Toast.makeText(this, org.blokada.R.string.password_prompt_incorrect, Toast.LENGTH_SHORT).show() // Use org.blokada.R
                binding.passwordEditText.text?.clear()
            }
        }
    }

    companion object {
        const val REQUEST_CODE_PASSWORD_PROMPT = 1001

        fun start(context: Context) {
            val intent = Intent(context, PasswordPromptActivity::class.java)
            context.startActivity(intent)
        }
    }
}