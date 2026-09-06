package com.cimalight

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object CloudflareSolver {
    private const val TAG = "CF_Solver_Fast"

    suspend fun solve(activity: Activity?, url: String, userAgent: String): Document? {
        return suspendCoroutine { continuation ->
            if (activity == null || activity.isFinishing) {
                continuation.resume(null)
                return@suspendCoroutine
            }

            Handler(Looper.getMainLooper()).post {
                val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
                    continuation.resume(null)
                    return@post
                }

                val webView = WebView(activity)
                webView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                webView.alpha = 0f
                webView.translationX = 10000f

                webView.isFocusable = false
                webView.isFocusableInTouchMode = false
                webView.isClickable = false

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    this.userAgentString = userAgent
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                var isSolved = false
                var isProcessingClick = false
                val pollingHandler = Handler(Looper.getMainLooper())

                fun finishSuccess(html: String?) {
                    if (!isSolved) {
                        isSolved = true
                        cookieManager.flush()
                        Log.d(TAG, "تم إنهاء Solver بنجاح.")

                        try {
                            pollingHandler.removeCallbacksAndMessages(null)
                            rootView.removeView(webView)
                            webView.destroy()
                        } catch (e: Exception) {}

                        if (html == null) {
                            continuation.resume(null)
                            return
                        }

                        val cleanHtml = html.removeSurrounding("\"")
                            .replace("\\u003C", "<")
                            .replace("\\u003E", ">")
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")

                        continuation.resume(Jsoup.parse(cleanHtml))
                    }
                }
                pollingHandler.postDelayed({
                    Log.e(TAG, "انتهى وقت Solver (Timeout).")
                    finishSuccess(null)
                }, 20000)

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
                                return // لا داعي لإعادة الجدولة إذا انتهينا
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
                            if(!isSolved) pollingHandler.postDelayed(this, 2000)
                        }
                    }
                    pollingHandler.post(runnable)
                }
                fun checkSuccessFast() {
                    if (isSolved) return
                    val currentCookies = cookieManager.getCookie(url) ?: ""
                    val hasClearanceCookie = currentCookies.contains("cf_clearance")

                    if (hasClearanceCookie) {
                        Log.d(TAG, "تم العثور على كوكي cf_clearance! إنهاء فوري.")
                        webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                            finishSuccess(html)
                        }
                        return
                    }
                    val jsCheck = """
                        (function(){
                            try{
                                var html = document.documentElement.innerHTML.toLowerCase();
                                var isCloudflare = html.includes("checking your browser") || html.includes("just a moment");
                                return isCloudflare.toString();
                            }catch(e){ return "true"; }
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(jsCheck) { res ->
                        val isCf = res?.replace("\"", "") == "true"

                        if (!isCf && !currentCookies.isNullOrEmpty()) {
                            Log.d(TAG, "اختفت رسالة كلاودفلير وتوجد كوكيز. إنهاء سريع.")
                            webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                                finishSuccess(html)
                            }
                            return@evaluateJavascript
                        }
                        if(!isSolved) pollingHandler.postDelayed({ checkSuccessFast() }, 500)
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d(TAG, "onPageFinished: $url")
                        isProcessingClick = false
                        startPolling()
                        checkSuccessFast()
                    }
                }

                rootView.addView(webView)
                webView.loadUrl(url)
            }
        }
    }
}