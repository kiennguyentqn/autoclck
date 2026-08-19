package com.example.quicktimerclick.activity

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.quicktimerclick.R

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val btnStart = findViewById<Button>(R.id.btnStart)

        btnStart.setOnClickListener {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                if (!Settings.canDrawOverlays(this)) {

                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )

                    startActivity(intent)

                } else {

                    // Bài sau mở FloatingService
                    startService(
                        Intent(this, com.example.quicktimerclick.service.FloatingService::class.java)
                    )
                }

            }

        }

    }
}