
package com.poolpredictor2

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class LauncherActivity : AppCompatActivity() {

    private var powerRatio: Float = 0.7f
    private var targetNumber: Int = 2

    // ThemeManager harus dibuat sebagai instance
    private lateinit var themeManager: ThemeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inisialisasi pengelola tema
        themeManager = ThemeManager(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Judul aplikasi
        val title = TextView(this).apply {
            text = "PoolPredictor2 - 2 Garis"
            textSize = 20f
        }
        layout.addView(title)

        // Pilihan tema
        val themeLabel = TextView(this).apply {
            text = "Tema:"
        }
        layout.addView(themeLabel)

        val themeSpinner = Spinner(this)

        val themes = listOf(
            "blood_moon",
            "obsidian_stealth"
        )

        val themeAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            themes
        )

        themeSpinner.adapter = themeAdapter

        themeSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    // Panggil fungsi melalui instance ThemeManager
                    val selectedTheme = themes[position]
                    themeManager.switchTheme(selectedTheme)
                }

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {}
            }

        layout.addView(themeSpinner)

        // Slider power
        val powerLabel = TextView(this).apply {
            text = "Power: 70%"
        }
        layout.addView(powerLabel)

        val powerSeek = SeekBar(this).apply {
            max = 100
            progress = 70
        }

        powerSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    powerRatio = progress / 100f
                    powerLabel.text = "Power: $progress%"
                }

                override fun onStartTrackingTouch(
                    seekBar: SeekBar?
                ) {}

                override fun onStopTrackingTouch(
                    seekBar: SeekBar?
                ) {}
            }
        )

        layout.addView(powerSeek)

        // Pilihan bola target
        val ballLabel = TextView(this).apply {
            text = "Bola target (warna garis ikut ini):"
        }
        layout.addView(ballLabel)

        val ballSpinner = Spinner(this)

        val balls = (1..15).map {
            it.toString()
        }

        ballSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            balls
        )

        ballSpinner.setSelection(1)

        ballSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    targetNumber = position + 1
                }

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {}
            }

        layout.addView(ballSpinner)

        // Tombol Start Overlay
        val startBtn = Button(this).apply {
            text = "Start Overlay (2 Garis)"
        }

        startBtn.setOnClickListener {

            val intent = Intent(
                this@LauncherActivity,
                PredictorService::class.java
            )

            intent.putExtra("power", powerRatio)
            intent.putExtra("targetNumber", targetNumber)

            startService(intent)

            Toast.makeText(
                this@LauncherActivity,
                "Overlay jalan, 2 garis",
                Toast.LENGTH_SHORT
            ).show()
        }

        layout.addView(startBtn)

        // Tombol Stop Overlay
        val stopBtn = Button(this).apply {
            text = "Stop Overlay"
        }

        stopBtn.setOnClickListener {

            stopService(
                Intent(
                    this@LauncherActivity,
                    PredictorService::class.java
                )
            )

            Toast.makeText(
                this@LauncherActivity,
                "Overlay stop",
                Toast.LENGTH_SHORT
            ).show()
        }

        layout.addView(stopBtn)

        // Informasi aplikasi
        val info = TextView(this).apply {
            text = """
                LOCK: 2 garis
                Warna mengikuti bola target
                Power-aware
                No Auto Play
                Data masih MOCK
            """.trimIndent()

            textSize = 12f
        }

        layout.addView(info)

        setContentView(layout)
    }
}
