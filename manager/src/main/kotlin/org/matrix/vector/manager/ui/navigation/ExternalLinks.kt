package org.matrix.vector.manager.ui.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.matrix.vector.manager.R
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.logW

/** External navigation for Home, issue reporting and store links; never opens our WebView. */
@Composable
fun rememberExternalLinkOpener(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { url ->
            openExternalLink(context, url, ServiceLocator.settings.preferDefaultBrowser.value)
        }
    }
}

private fun openExternalLink(context: Context, url: String, preferBrowser: Boolean) {
    try {
        val uri = Uri.parse(url)
        require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank())
        val target = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(if (preferBrowser) browserIntent(context, target) else target)
    } catch (error: ActivityNotFoundException) {
        failed(context, error)
    } catch (error: SecurityException) {
        failed(context, error)
    } catch (error: IllegalArgumentException) {
        failed(context, error)
    }
}

private fun browserIntent(context: Context, target: Intent): Intent {
    // Resolve a generic HTTPS page, not the GitHub URL (which a domain app may claim).
    // This is only a PackageManager query: no request is sent to the placeholder host.
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val pm = context.packageManager
    val browsers = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo.packageName }.distinct()
    val preferred = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName?.takeIf { it in browsers }
    if (preferred != null) return Intent(target).setPackage(preferred)
    if (browsers.isEmpty()) throw ActivityNotFoundException("No HTTPS browser available")
    if (browsers.size == 1) return Intent(target).setPackage(browsers.single())
    // Do not target a launcher activity: it may ignore the download URI entirely.
    val choices = browsers.map { Intent(target).setPackage(it) }
    return Intent.createChooser(choices.first(), context.getString(R.string.links_choose_browser))
        .putExtra(Intent.EXTRA_INITIAL_INTENTS, choices.drop(1).toTypedArray())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private fun failed(context: Context, error: Exception) {
    logW("links: external link launch failed", error)
    Toast.makeText(context, R.string.links_open_failed, Toast.LENGTH_LONG).show()
}
