package com.anime4up

import android.R
import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal data class SolverResult(val finalUrl: String, val cookies: String?)

internal object CloudflareSolver {
    private const val TAG = "CF_Cookie_Hunter_Hidden"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    suspend fun solve(activity: Activity?, initialUrl: String, userAgent: String): SolverResult? {

        return suspendCoroutine { continuation ->
            Log.d(TAG, "🕵️ بدء رحلة صيد الكوكيز للرابط: $initialUrl (في الخلفية)")

            if (activity == null || activity.isFinishing) {
                Log.e(TAG, "Activity غير متاح.")
                continuation.resume(null)
                return@suspendCoroutine
            }

            Handler(Looper.getMainLooper()).post {
                val rootView = activity.findViewById<ViewGroup>(R.id.content) ?: run {
                    continuation.resume(null)
                    return@post
                }

                val webView = WebView(activity)
                webView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                webView.alpha = 0.01f // شفافية شبه كاملة لتجنب كشف الروبوتات
                webView.translationX = 10000f // إزاحة النافذة خارج الشاشة تماماً
                webView.translationY = 10000f
                webView.isFocusable = false // منع التفاعل وسحب التركيز
                webView.isFocusableInTouchMode = false
                webView.isClickable = false

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    this.userAgentString = USER_AGENT
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                var isSolved = false
                var isProcessingClick = false
                val pollingHandler = Handler(Looper.getMainLooper())

                fun finishSuccess(finalUrl: String, reason: String = "غير معروف") {
                    if (!isSolved) {
                        isSolved = true
                        Log.i(TAG, "إغلاق المخفي | السبب: $reason | الرابط النهائي: $finalUrl")

                        cookieManager.flush()
                        val finalCookies = cookieManager.getCookie(finalUrl)

                        if (!finalCookies.isNullOrEmpty()) {
                            Log.w(TAG, "🍪 تم صيد الكوكيز بنجاح في الخلفية: $finalCookies")
                        } else {
                            Log.e(TAG, "⚠️ لم يتم العثور على أي كوكيز!")
                        }

                        try {
                            pollingHandler.removeCallbacksAndMessages(null)
                            rootView.removeView(webView)
                            webView.destroy()
                        } catch (e: Exception) {}
                        continuation.resume(SolverResult(finalUrl, finalCookies))
                    }
                }
                pollingHandler.postDelayed({
                    finishSuccess(webView.url ?: initialUrl, "Timeout - 60s")
                }, 60000)

                fun simulateRealTouch(view: WebView, cssX: Float, cssY: Float) {
                    val density = activity.resources.displayMetrics.density
                    val realX = cssX * density
                    val realY = cssY * density
                    val downTime = SystemClock.uptimeMillis()
                    val eventTime = SystemClock.uptimeMillis() + 50
                    val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, realX, realY, 0)
                    view.dispatchTouchEvent(downEvent)
                    view.postDelayed({
                        val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, realX, realY, 0)
                        view.dispatchTouchEvent(upEvent)
                        downEvent.recycle()
                        upEvent.recycle()
                    }, 50)
                }

                val targetCssPath = "html > body > div:nth-of-type(1) > div > div:nth-of-type(2) > div"

                fun startPolling() {
                    val runnable = object : Runnable {
                        override fun run() {
                            if (isSolved || isProcessingClick) {
                                pollingHandler.postDelayed(this, 2000)
                                return
                            }

                            val jsGetCoords = """
                                (function(){
                                    try{
                                        var box = document.querySelector("$targetCssPath");
                                        if(!box) return "NO_BOX";
                                        var r = box.getBoundingClientRect();
                                        if(r.width === 0 && r.height === 0) return "NO_BOX";
                                        var size = Math.min(36, Math.max(18, Math.round(r.height * 0.55)));
                                        var margin = Math.round(Math.max(8, r.width * 0.03));
                                        var centerY = r.top + (r.height / 2);
                                        var rightSideX = r.right - (size / 2) - margin;
                                        var leftSideX = r.left + (size / 2) + margin;
                                        return rightSideX + "," + centerY + "|" + leftSideX + "," + centerY;
                                    }catch(e){ return "ERROR"; }
                                })();
                            """.trimIndent()

                            webView.evaluateJavascript(jsGetCoords) { res ->
                                try {
                                    val clean = res?.removeSurrounding("\"")
                                    if (clean != null && clean.contains("|")) {
                                        Log.d(TAG, "🤖 جاري النقر التلقائي في الخلفية...")
                                        isProcessingClick = true
                                        val sides = clean.split("|")
                                        val (rx, ry) = sides[0].split(",").map { it.toFloatOrNull() }
                                        val (lx, ly) = sides[1].split(",").map { it.toFloatOrNull() }
                                        if (rx != null && ry != null && lx != null && ly != null) {
                                            simulateRealTouch(webView, rx, ry)
                                            pollingHandler.postDelayed({
                                                simulateRealTouch(webView, lx, ly)
                                                pollingHandler.postDelayed({ isProcessingClick = false }, 3000)
                                            }, 250)
                                        } else { isProcessingClick = false }
                                    }
                                } catch (e: Exception) { isProcessingClick = false }
                            }
                            pollingHandler.postDelayed(this, 2000)
                        }
                    }
                    pollingHandler.post(runnable)
                }

                fun checkBypassSuccess() {
                    if (isSolved) return

                    val currentLiveUrl = webView.url ?: initialUrl
                    val currentCookies = cookieManager.getCookie(currentLiveUrl)

                    if (currentCookies != null && currentCookies.contains("cf_clearance")) {
                        finishSuccess(currentLiveUrl, "تم صيد الكوكيز بنجاح")
                        return
                    }

                    pollingHandler.postDelayed({ checkBypassSuccess() }, 500)
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (url != null && url != initialUrl) {
                            Log.w(TAG, "🔄 إعادة توجيه في الخلفية إلى: $url")
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isProcessingClick = false
                        startPolling()
                        checkBypassSuccess()
                    }
                }
                rootView.addView(webView)
                val customHeaders = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                    "Accept-Encoding" to "gzip, deflate, br",
                    "Accept-Language" to "ar-EG,ar;q=0.9",
                    "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
                    "Sec-Ch-Ua-Mobile" to "?1",
                    "Sec-Ch-Ua-Platform" to "\"Android\"",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "none",
                    "Sec-Fetch-User" to "?1",
                    "Upgrade-Insecure-Requests" to "1",
                    "User-Agent" to USER_AGENT
                )
                webView.loadUrl(initialUrl, customHeaders)
            }
        }
    }
}