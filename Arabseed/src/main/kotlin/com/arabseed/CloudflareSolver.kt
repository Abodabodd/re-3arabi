package com.arabseed

import android.R
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object CloudflareSolver {
    private const val TAG = "CF_Solver_Hidden"
    const val EXACT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    suspend fun solve(activity: Activity?, url: String): Document? {
        return suspendCoroutine { continuation ->
            if (activity == null || activity.isFinishing) {
                continuation.resume(null)
                return@suspendCoroutine
            }

            Handler(Looper.getMainLooper()).post {
                val rootView = activity.findViewById<ViewGroup>(R.id.content) ?: run {
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
                webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = EXACT_USER_AGENT
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
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
                pollingHandler.postDelayed({ finishSuccess(null) }, 60000)

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

                var lastUrl: String? = null
                var stableSince = 0L
                var fetched = false

                fun waitUntilReady() {
                    if (isSolved) return
                    val js = """
                        (function(){
                            try{
                                var html = document.documentElement.innerHTML || "";
                                var stillCloudflare = html.toLowerCase().includes("cloudflare") || html.toLowerCase().includes("just a moment");
                                return location.href + "|" + document.readyState + "|" + stillCloudflare;
                            }catch(e){ return location.href + "|loading|true"; }
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(js) { res ->
                        if (res == null) {
                            pollingHandler.postDelayed({ waitUntilReady() }, 1000)
                            return@evaluateJavascript
                        }

                        val parts = res.replace("\"", "").split("|")
                        if (parts.size < 3) {
                            pollingHandler.postDelayed({ waitUntilReady() }, 1000)
                            return@evaluateJavascript
                        }

                        val (currentUrl, ready, stillCloudflare) = parts
                        val now = SystemClock.uptimeMillis()
                        if (currentUrl != lastUrl) {
                            lastUrl = currentUrl
                            stableSince = now
                        }
                        val stableTime = now - stableSince
                        if (stillCloudflare == "false" && ready == "complete" && stableTime > 1500 && !fetched) {
                            fetched = true
                            pollingHandler.postDelayed({
                                webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                                    finishSuccess(html)
                                }
                            }, 500)
                            return@evaluateJavascript
                        }
                        pollingHandler.postDelayed({ waitUntilReady() }, 1000)
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isProcessingClick = false
                        startPolling()
                        waitUntilReady()
                    }
                }

                val customHeaders = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                    "Accept-Language" to "ar-EG,ar;q=0.9",
                    "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
                    "Sec-Ch-Ua-Mobile" to "?1",
                    "Sec-Ch-Ua-Platform" to "\"Android\"",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "none",
                    "Sec-Fetch-User" to "?1",
                    "Upgrade-Insecure-Requests" to "1"
                )

                rootView.addView(webView)
                webView.loadUrl(url, customHeaders)
            }
        }
    }
}