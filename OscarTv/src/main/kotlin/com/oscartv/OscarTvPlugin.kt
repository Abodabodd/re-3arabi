package com.oscartv

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OscarTvPlugin : Plugin() {

    companion object {
        const val TELEGRAM = "https://t.me/fullappk"
        const val DONATE = "https://creators.sa/x3onq"

        private fun dp(ctx: Context, v: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
        ).toInt()

        private fun pill(colors: IntArray, radiusPx: Float): GradientDrawable =
            GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
                cornerRadius = radiusPx
            }

        private fun open(ctx: Context, url: String) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Throwable) {
            }
        }

        fun dialog(ctx: Context) {
            try {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(ctx, 26), dp(ctx, 28), dp(ctx, 26), dp(ctx, 14))
                    background = GradientDrawable().apply {
                        setColor(0xFF1B1B2F.toInt())
                        cornerRadius = dp(ctx, 26).toFloat()
                    }
                }

                card.addView(TextView(ctx).apply {
                    text = "🎬"
                    textSize = 42f
                    gravity = Gravity.CENTER
                })
                card.addView(TextView(ctx).apply {
                    text = "Oscar TV"
                    textSize = 23f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, dp(ctx, 10), 0, 0)
                })
                card.addView(TextView(ctx).apply {
                    text = "إضافة عربية بواسطة mo7AmMeD64"
                    textSize = 13f
                    setTextColor(0xFF9A9AB0.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, dp(ctx, 4), 0, dp(ctx, 16))
                })
                card.addView(TextView(ctx).apply {
                    text = "انضم لقناة التليجرام لكل جديد و الدعم\nولدعم استمرار الإضافة و التحديثات تبرع لنا 💚"
                    textSize = 14f
                    setTextColor(0xFFD0D0E0.toInt())
                    gravity = Gravity.CENTER
                    setLineSpacing(dp(ctx, 4).toFloat(), 1f)
                    setPadding(dp(ctx, 6), 0, dp(ctx, 6), dp(ctx, 20))
                })

                val dlg = AlertDialog.Builder(ctx).create()

                fun bigButton(label: String, colors: IntArray, url: String): Button =
                    Button(ctx).apply {
                        text = label
                        setTextColor(Color.WHITE)
                        typeface = Typeface.DEFAULT_BOLD
                        textSize = 15f
                        isAllCaps = false
                        stateListAnimator = null
                        background = pill(colors, dp(ctx, 24).toFloat())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 48)
                        ).apply { bottomMargin = dp(ctx, 10) }
                        setOnClickListener { open(ctx, url); dlg.dismiss() }
                    }

                card.addView(bigButton("✈️  قناة التليجرام", intArrayOf(0xFF2AABEE.toInt(), 0xFF1D86C9.toInt()), TELEGRAM))
                card.addView(bigButton("💚  تبرع لنا", intArrayOf(0xFF1FBF75.toInt(), 0xFF0E9F5D.toInt()), DONATE))

                card.addView(TextView(ctx).apply {
                    text = "إغلاق"
                    textSize = 14f
                    setTextColor(0xFF8A8AA0.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
                    setOnClickListener { dlg.dismiss() }
                })

                dlg.setView(card)
                dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                dlg.show()
            } catch (_: Throwable) {
            }
        }
    }

    override fun load() {
        registerMainAPI(OscarTvProvider())
        openSettings = { ctx -> dialog(ctx) }
    }
}
