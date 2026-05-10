package com.wifishare.ui

import android.app.Activity
import android.content.Context
import android.util.DisplayMetrics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.wifishare.BuildConfig

/**
 * Bottom-of-screen ad banner. Renders nothing when no banner ad-unit
 * is configured (see app/build.gradle.kts → defaultConfig.admobBannerUnitId).
 *
 * Once a real unit ID is set, the banner sizes itself adaptively to the
 * current screen width and loads through Google Mobile Ads.
 */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    val unitId = BuildConfig.ADMOB_BANNER_UNIT_ID
    if (unitId.isBlank()) return  // ads disabled → nothing rendered

    val context = LocalContext.current
    val adSize = remember(context) { adaptiveAdSize(context) }

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            AdView(ctx).apply {
                setAdSize(adSize)
                adUnitId = unitId
                loadAd(AdRequest.Builder().build())
            }
        },
    )
}

private fun adaptiveAdSize(context: Context): AdSize {
    val activity = context as? Activity
    val widthPx: Int = if (activity != null) {
        val display = activity.windowManager.defaultDisplay
        val outMetrics = DisplayMetrics().also { display.getMetrics(it) }
        outMetrics.widthPixels
    } else {
        context.resources.displayMetrics.widthPixels
    }
    val density = context.resources.displayMetrics.density
    val widthDp = (widthPx / density).toInt().coerceAtLeast(320)
    return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
}
