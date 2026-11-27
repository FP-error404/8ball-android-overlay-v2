package com.poolpredictor2

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

// Project 2 - LauncherActivity
// LOCK: 2 garis doang, no Auto Play
// Isinya: pilih tema, slider power, tombol Start/Stop service

class LauncherActivity : AppCompatActivity() {

    private var powerRatio: Float = 0.7f
    private var targetNumber: Int = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "PoolPredictor2 - 2 Garis"
            textSize = 20f
        }
        layout.addView(title)

        // Pilih tema
        val themeLabel = TextView(this).apply { text = "Tema:" }
        layout.addView(themeLabel)
        val themeSpinner = Spinner(this)
        val themes = listOf("blood_moon", "obsidian_stealth")
        themeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, themes)
        themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                ThemeManager.switchTheme(themes[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        layout.addView(themeSpinner)

        // Slider power - ini yang bikin garis ngikutin power, bukan full power doang
        val powerLabel = TextView(this).apply { text = "Power: 70%" }
        layout.addView(powerLabel)
        val powerSeek = SeekBar(this).apply {
            max = 100
            progress = 70
        }
        powerSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                powerRatio = p / 100f
                powerLabel.text = "Power: $p%"
                // TODO: kirim power ke PredictorService via broadcast / singleton
                // Untuk sekarang, PredictorService baca dari sini pas di-start
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        layout.addView(powerSeek)

        // Pilih bola target - warna garis ikut bola ini
        val ballLabel = TextView(this).apply { text = "Bola target (warna garis ikut ini):" }
        layout.addView(ballLabel)
        val ballSpinner = Spinner(this)
        val balls = (1..15).map { it.toString() }
        ballSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, balls)
        ballSpinner.setSelection(1) // default bola 2 biru
        ballSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                targetNumber = pos + 1
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        layout.addView(ballSpinner)

        // Tombol Start / Stop - Auto Play dicoret, jangan ada tombol itu
        val startBtn = Button(this).apply { text = "Start Overlay (2 Garis)" }
        startBtn.setOnClickListener {
            val intent = Intent(this, PredictorService::class.java)
            // Simpan power + target ke Config biar Service bisa baca
            // Untuk skeleton, kita simpan di Intent extra, Service nanti baca
            intent.putExtra("power", powerRatio)
            intent.putExtra("targetNumber", targetNumber)
            startService(intent)
            Toast.makeText(this, "Overlay jalan, 2 garis", Toast.LENGTH_SHORT).show()
        }
        layout.addView(startBtn)

        val stopBtn = Button(this).apply { text = "Stop Overlay" }
        stopBtn.setOnClickListener {
            stopService(Intent(this, PredictorService::class.java))
            Toast.makeText(this, "Overlay stop", Toast.LENGTH_SHORT).show()
        }
        layout.addView(stopBtn)

        val info = TextView(this).apply {
            text = "LOCK: 2 garis, warna ikut bola, power-aware, no Auto Play. Data masih MOCK."
            textSize = 12f
        }
        layout.addView(info)

        setContentView(layout)
    }
}
