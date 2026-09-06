package com.anime3rb

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import androidx.preference.PreferenceManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.network.WebViewResolver
import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentContainerView
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.EditTextPreference
import com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesApi.Companion.userAgent
const val COOKIE_KEY = "anime3rb_cookie_v2"
const val USER_AGENT_KEY = "anime3rb_ua_v2"

const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

class Anime3rbSettingsDialog : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val fragmentContainer = FragmentContainerView(requireContext())
        fragmentContainer.id = View.generateViewId()
        fragmentContainer.layoutParams = ViewGroup.LayoutParams(-1, -1)
        return fragmentContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment())
            .commit()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(-1, -1)
        dialog?.window?.setBackgroundDrawableResource(android.R.color.white)
    }

    class PrefsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {

            val ctx = requireContext()
            val screen = preferenceManager.createPreferenceScreen(ctx)
            preferenceScreen = screen

            val category = PreferenceCategory(ctx)
            category.title = "Cloudflare & Cookies"
            screen.addPreference(category)
            val solvePref = Preference(ctx).apply {
                title = "حل  الكابتشا (WebView)"
                summary = "اضغط لفتح الموقع وتسجيل الدخول يدوياً."
                setOnPreferenceClickListener {
                    val webDialog = WebViewCaptureDialog()
                    webDialog.setStyle(
                        STYLE_NORMAL,
                        android.R.style.Theme_Material_Light_NoActionBar_Fullscreen
                    )
                    webDialog.show(parentFragmentManager, "webview_fullscreen")
                    true
                }
            }
            category.addPreference(solvePref)
            val cookieEditPref = EditTextPreference(ctx).apply {
                key = COOKIE_KEY // يربط تلقائياً بالـ SharedPrefs
                title = "تعديل الكوكيز يدوياً"
                summary = "اضغط لرؤية الكوكيز المحفوظة أو تعديلها."
                dialogTitle = "Cookies"
            }
            category.addPreference(cookieEditPref)
            val statusPref = Preference(ctx).apply {
                title = "حالة الكوكيز"
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                val cookie = prefs.getString(COOKIE_KEY, "")
                summary =
                    if (cookie.isNullOrEmpty()) "❌ غير محفوظة" else "✅ محفوظة (${cookie.take(20)}...)"
                isEnabled = false
            }
            category.addPreference(statusPref)

            val closePref = Preference(ctx).apply {
                title = "إغلاق"
                setOnPreferenceClickListener {
                    (parentFragment as? DialogFragment)?.dismiss()
                    true
                }
            }
            screen.addPreference(closePref)
        }
    }



    class WebViewCaptureDialog : DialogFragment() {
        private lateinit var webView: WebView
        private val cleanUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val ctx = requireContext()
            val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = ViewGroup.LayoutParams(-1, -1); setBackgroundColor(Color.WHITE) }

            val toolbar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(30, 30, 30, 30)
                setBackgroundColor(Color.parseColor("#202020"))
                gravity = Gravity.CENTER_VERTICAL
            }

            val closeBtn = Button(ctx).apply { text = "إلغاء"; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { dismiss() } }
            val titleView = TextView(ctx).apply { text = "محاكاة اللمس..."; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.YELLOW); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            val saveBtn = Button(ctx).apply { text = "حفظ"; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#007AFF")); setOnClickListener { captureAndClose() } }

            toolbar.addView(closeBtn); toolbar.addView(titleView); toolbar.addView(saveBtn)

            val webContainer = FrameLayout(ctx).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
            webView = WebView(ctx).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }

            val settings = webView.settings
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString = cleanUserAgent
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webView.addJavascriptInterface(WebAppInterface(webView), "AndroidTouch")

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            cookieManager.removeAllCookies(null)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    val jsTouchLogic = """
                    (function() {
                        function drawRedDot(x, y) {
                            var dot = document.createElement('div');
                            dot.style = "position:fixed; left:" + x + "px; top:" + y + "px; width:20px; height:20px; background:red; border:2px solid white; border-radius:50%; z-index:99999999; pointer-events:none; opacity:0.8;";
                            document.body.appendChild(dot);
                            setTimeout(function(){ dot.remove(); }, 300);
                        }

                        setInterval(function() {
                            var xpath = "//*[contains(text(), 'Verify') or contains(text(), 'تحقق')]";
                            var textEl = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;

                            if (textEl) {
                                var rect = textEl.getBoundingClientRect();
                                var targetY = rect.top + (rect.height / 2); // نفس سطر النص
                                var xLeft = rect.left - 40; // إزاحة لليسار أكثر
                                var xRight = rect.right + 40; 
                                drawRedDot(xLeft, targetY);
                                if(window.AndroidTouch) window.AndroidTouch.performClick(xLeft, targetY);
                                setTimeout(function() {
                                    drawRedDot(xRight, targetY);
                                    if(window.AndroidTouch) window.AndroidTouch.performClick(xRight, targetY);
                                }, 200);
                            } 
                        }, 2000); // كل ثانيتين
                    })();
                """
                    view?.evaluateJavascript(jsTouchLogic, null)

                    val cookies = cookieManager.getCookie(url) ?: ""
                    if (cookies.contains("cf_clearance")) {
                        titleView.text = "✅ تم الحل! حفظ..."
                        titleView.setTextColor(Color.GREEN)
                        view?.postDelayed({ captureAndClose() }, 1000)
                    }
                }
            }

            webView.loadUrl("https://anime3rb.com")
            webContainer.addView(webView)
            root.addView(toolbar)
            root.addView(webContainer)
            return root
        }

        private fun captureAndClose() {
            try {
                CookieManager.getInstance().flush()
                val url = webView.url ?: "https://anime3rb.com"
                val cookieStr = CookieManager.getInstance().getCookie(url) ?: ""
                if (cookieStr.isNotBlank()) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    prefs.edit().putString(COOKIE_KEY, cookieStr).apply()
                    prefs.edit().putString(USER_AGENT_KEY, cleanUserAgent).apply()
                    Toast.makeText(context, "تم الحفظ بنجاح", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            } catch (e: Exception) {}
        }
        class WebAppInterface(private val view: WebView) {
            @JavascriptInterface
            fun performClick(x: Float, y: Float) {
                Handler(Looper.getMainLooper()).post {
                    val density = view.resources.displayMetrics.density
                    val realX = x * density
                    val realY = y * density

                    val downTime = SystemClock.uptimeMillis()
                    val eventTime = SystemClock.uptimeMillis() + 100
                    val motionEventDown = MotionEvent.obtain(
                        downTime,
                        eventTime,
                        MotionEvent.ACTION_DOWN,
                        realX,
                        realY,
                        0
                    )
                    val motionEventUp = MotionEvent.obtain(
                        downTime,
                        eventTime + 100,
                        MotionEvent.ACTION_UP,
                        realX,
                        realY,
                        0
                    )
                    view.dispatchTouchEvent(motionEventDown)
                    view.dispatchTouchEvent(motionEventUp)
                    motionEventDown.recycle()
                    motionEventUp.recycle()
                }
            }
        }
    }
}