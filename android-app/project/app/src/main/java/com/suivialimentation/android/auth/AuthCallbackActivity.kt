package com.suivialimentation.android.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.suivialimentation.android.MainActivity

class AuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = Intent(this, MainActivity::class.java).apply {
            data = intent?.data
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(target)
        finish()
    }
}
