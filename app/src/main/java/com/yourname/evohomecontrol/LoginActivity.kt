package com.yourname.evohomecontrol

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.yourname.evohomecontrol.api.EvohomeApiClient
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    
    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var loginButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var rememberMeCheckbox: CheckBox
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        
        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginButton = findViewById(R.id.loginButton)
        progressBar = findViewById(R.id.progressBar)
        errorText = findViewById(R.id.errorText)
        rememberMeCheckbox = findViewById(R.id.rememberMeCheckbox)
        
        // Check if already logged in
        checkExistingLogin()
        
        loginButton.setOnClickListener {
            attemptLogin()
        }
    }
    
    private fun checkExistingLogin() {
        val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
        val savedEmail = prefs.getString("saved_email", null)
        val savedPassword = prefs.getString("saved_password", null)
        val rememberMe = prefs.getBoolean("remember_me", false)
        
        if (rememberMe && !savedEmail.isNullOrEmpty() && !savedPassword.isNullOrEmpty()) {
            // Auto-fill and auto-login
            emailInput.setText(savedEmail)
            passwordInput.setText(savedPassword)
            rememberMeCheckbox.isChecked = true
            
            // Automatically attempt login
            progressBar.visibility = View.VISIBLE
            loginButton.isEnabled = false
            attemptLoginWithCredentials(savedEmail, savedPassword)
        } else if (!savedEmail.isNullOrEmpty()) {
            // Just pre-fill email if available
            emailInput.setText(savedEmail)
            rememberMeCheckbox.isChecked = rememberMe
        }
    }
    
    private fun attemptLogin() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        
        if (email.isEmpty() || password.isEmpty()) {
            errorText.text = "Please enter email and password"
            errorText.visibility = View.VISIBLE
            return
        }
        
        progressBar.visibility = View.VISIBLE
        loginButton.isEnabled = false
        errorText.visibility = View.GONE
        
        attemptLoginWithCredentials(email, password)
    }
    
    private fun attemptLoginWithCredentials(email: String, password: String) {
        lifecycleScope.launch {
            try {
                val tokenResponse = EvohomeApiClient.apiService.getTokens(
                    username = email,
                    password = password
                )
                
                if (tokenResponse.isSuccessful && tokenResponse.body() != null) {
                    val tokens = tokenResponse.body()!!
                    val accessToken = tokens.access_token
                    val refreshToken = tokens.refresh_token
                    
                    val accountResponse = EvohomeApiClient.apiService.getUserAccount(
                        auth = "bearer $accessToken"
                    )
                    
                    if (accountResponse.isSuccessful && accountResponse.body() != null) {
                        val account = accountResponse.body()!!
                        
                        // Save session and credentials if "Remember Me" is checked
                        saveSession(accessToken, refreshToken, account.userId)
                        
                        if (rememberMeCheckbox.isChecked) {
                            saveCredentials(email, password)
                        } else {
                            clearSavedCredentials()
                        }
                        
                        // Navigate to main screen
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        finish()
                    } else {
                        showError("Failed to get account info: ${accountResponse.code()}")
                    }
                } else {
                    showError("Login failed: Invalid credentials")
                    clearSavedCredentials()
                }
            } catch (e: Exception) {
                showError("Network error: ${e.message}")
            } finally {
                progressBar.visibility = View.GONE
                loginButton.isEnabled = true
            }
        }
    }
    
    private fun saveSession(accessToken: String, refreshToken: String, userId: String) {
        val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
        prefs.edit().apply {
            putString("access_token", accessToken)
            putString("refresh_token", refreshToken)
            putString("user_id", userId)
            putLong("login_time", System.currentTimeMillis())
            apply()
        }
    }
    
    private fun saveCredentials(email: String, password: String) {
        val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
        prefs.edit().apply {
            putString("saved_email", email)
            putString("saved_password", password)
            putBoolean("remember_me", true)
            apply()
        }
    }
    
    private fun clearSavedCredentials() {
        val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
        prefs.edit().apply {
            remove("saved_email")
            remove("saved_password")
            putBoolean("remember_me", false)
            apply()
        }
    }
    
    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }
}