package com.example.chatapp

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.data.AccountScopedSettings

class PersonalizationActivity : AppCompatActivity() {

    private lateinit var etInstructions: EditText
    private lateinit var accountSettings: AccountScopedSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_personalization)

        window.statusBarColor = Color.TRANSPARENT

        etInstructions = findViewById(R.id.etInstructions)
        accountSettings = AccountScopedSettings(this)

        etInstructions.setText(accountSettings.getUserInstructions())
        
        // Translate UI
        findViewById<android.widget.TextView>(R.id.tvToolbarTitle)?.text = LocaleHelper.getString(this, "label_personalization")
        findViewById<android.widget.TextView>(R.id.tvInstructionsLabel)?.text = LocaleHelper.getString(this, "label_personalization_instructions")

        // Back button
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // Save button (checkmark)
        findViewById<View>(R.id.btnSave).setOnClickListener {
            val text = etInstructions.text.toString().trim()
            accountSettings.saveUserInstructions(text)
            Toast.makeText(this, LocaleHelper.getString(this, "toast_instructions_saved"), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
