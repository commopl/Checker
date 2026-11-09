package com.example.mediaprojectionsimple

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val captureRequest = 1401
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var floatingSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var copyButton: Button
    private val background = Color.rgb(13, 18, 29)
    private val surface = Color.rgb(28, 36, 53)
    private val accent = Color.rgb(77, 228, 196)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        window.statusBarColor = background
        window.navigationBarColor = background
        val dp = resources.displayMetrics.density
        fun px(value: Int) = (value * dp).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(32), px(22), px(32))
            setBackgroundColor(background)
        }
        fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(null, Typeface.BOLD)
                setPadding(0, px(5), 0, px(11))
            }
        fun button(text: String, filled: Boolean, click: () -> Unit): Button = Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 15f
            setTextColor(if (filled) background else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = px(14).toFloat()
                setColor(if (filled) accent else surface)
            }
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(-1, px(56)).apply { bottomMargin = px(12) }
        }

        content.addView(label("◉   LIVE SCREEN MIRROR", 25f, Color.WHITE, true))
        content.addView(label("Podgląd własnego ekranu lub udostępnianie go w lokalnej sieci Wi-Fi. Bez konta i bez zapisu nagrań.", 15f, 0xFFADBCD0.toInt()))
        content.addView(label("TRYB PRACY", 12f, accent, true))

        floatingSwitch = Switch(this).apply {
            text = "Pływające okno na moim telefonie"
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = false
            setPadding(px(12), px(12), px(12), px(12))
            background = GradientDrawable().apply { cornerRadius = px(14).toFloat(); setColor(surface) }
        }
        content.addView(floatingSwitch, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(17) })
        content.addView(label("Wyłączone: przeglądarka na innym urządzeniu w tym samym Wi-Fi. Włączone: małe okno nad aplikacjami.", 13f, 0xFFADBCD0.toInt()))
        content.addView(button("▶   Rozpocznij", true) { requestCapture() })
        content.addView(button("■   Zatrzymaj", false) { stopService(Intent(this, CaptureService::class.java)); refreshStatus() })

        content.addView(label("STATUS", 12f, accent, true))
        statusText = label("Nieudostępniany", 16f, Color.WHITE, true)
        content.addView(statusText)
        urlText = label("", 15f, 0xFFADBCD0.toInt())
        urlText.setTextIsSelectable(true)
        content.addView(urlText)
        copyButton = button("Kopiuj adres do wysłania", false) {
            val link = CaptureService.currentShareUrl ?: return@button
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Link do ekranu", link))
            Toast.makeText(this, "Link skopiowany", Toast.LENGTH_SHORT).show()
        }
        content.addView(copyButton)
        content.addView(label("🔒  Każda sesja wymaga zgody Androida. Udostępnianie działa tylko w bieżącej sieci Wi-Fi i korzysta z losowego klucza w adresie. Link udostępniaj wyłącznie zaufanym osobom. Transmisja HTTP w Wi-Fi nie jest szyfrowana.", 12f, 0xFFADBCD0.toInt()))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1520)
        }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun requestCapture() {
        if (CaptureService.isRunning) {
            Toast.makeText(this, "Najpierw zatrzymaj bieżącą sesję", Toast.LENGTH_LONG).show()
            return
        }
        if (floatingSwitch.isChecked && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Zezwól na wyświetlanie nad innymi aplikacjami, potem kliknij Rozpocznij", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Used for dependency-free platform MediaProjection consent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequest || resultCode != RESULT_OK || data == null) return
        val floating = floatingSwitch.isChecked
        startForegroundService(Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_CODE, resultCode)
            putExtra(CaptureService.EXTRA_DATA, data)
            putExtra(CaptureService.EXTRA_FLOATING, floating)
        })
        if (floating) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            statusText.postDelayed({ refreshStatus() }, 700)
        }
    }

    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        val running = CaptureService.isRunning
        val link = CaptureService.currentShareUrl
        statusText.text = when {
            !running -> "●   Zatrzymano"
            link != null -> "●   Transmisja Wi-Fi aktywna"
            else -> "●   Podgląd aktywny"
        }
        statusText.setTextColor(if (running) accent else 0xFFADBCD0.toInt())
        urlText.text = if (link != null) "Otwórz na drugim urządzeniu:\n$link" else ""
        copyButton.visibility = if (link != null) View.VISIBLE else View.GONE
    }
}
