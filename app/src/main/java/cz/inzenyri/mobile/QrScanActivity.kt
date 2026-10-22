package cz.inzenyri.mobile

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/** Camera lifecycle, permission requests and scan results stay with CaptureActivity. */
class QrScanActivity : CaptureActivity() {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun initializeContent(): DecoratedBarcodeView {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(24, 23, 39))
                setPadding(dp(20), dp(12), dp(20), dp(16))
            }
        fun text(value: String, size: Float, color: Int) =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
            }
        root.addView(text("Přihlášení QR kódem", 24f, Color.WHITE))
        root.addView(
            text(
                "Namiř fotoaparát na kód z nastavení aplikace Inženýři.",
                15f,
                Color.rgb(195, 190, 215),
            ),
        )
        val scanner =
            DecoratedBarcodeView(this).apply {
                statusView.visibility = View.GONE
            }
        root.addView(
            scanner,
            LinearLayout.LayoutParams(-1, 0, 1f).apply {
                topMargin = dp(16)
                bottomMargin = dp(16)
            },
        )
        root.addView(
            text(
                "Umísti celý QR kód do rámečku. Přihlášení proběhne automaticky.",
                14f,
                Color.rgb(195, 190, 215),
            ),
        )
        fun action(title: String, onClick: () -> Unit) =
            Button(this).apply {
                text = title
                isAllCaps = false
                textSize = 16f
                setTextColor(Color.WHITE)
                background =
                    GradientDrawable().apply {
                        setColor(Color.rgb(65, 49, 104))
                        cornerRadius = dp(16).toFloat()
                    }
                setOnClickListener { onClick() }
            }
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            var torchOn = false
            val torchButton = action("Zapnout svítilnu") {}
            torchButton.setOnClickListener {
                torchOn = !torchOn
                if (torchOn) scanner.setTorchOn() else scanner.setTorchOff()
                torchButton.text = if (torchOn) "Vypnout svítilnu" else "Zapnout svítilnu"
            }
            root.addView(
                torchButton,
                LinearLayout.LayoutParams(-1, dp(52)).apply {
                    bottomMargin = dp(12)
                },
            )
        }
        root.addView(
            action("Zpět k přihlášení") { finish() },
            LinearLayout.LayoutParams(-1, dp(52)),
        )
        setContentView(root)
        return scanner
    }
}
