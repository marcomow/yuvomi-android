package io.github.marcomow.yuvomi.feature.contacts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The web app's `tel:`, `mailto:` and OpenStreetMap links as Android intents (CONT-R6):
 * the dialler opens with the number (no call permission), the mail app with the address,
 * and a maps app (or the browser) with the address.
 */
object ContactIntents {
    fun dialIntent(raw: String): Intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(ContactPhones.dial(raw), "+*#")))

    fun emailIntent(address: String): Intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(address, "@.+-_")))

    /** A `geo:` search where a maps app exists, else the web app's OpenStreetMap search. */
    fun mapsIntents(address: String): List<Intent> = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address))),
        Intent(Intent.ACTION_VIEW, Uri.parse(ContactsRules.mapsUrl(address))),
    )

    fun webIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(ContactsRules.externalHref(url)))

    /** Starts the first intent some app answers; false when none does. */
    fun start(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                continue
            } catch (_: SecurityException) {
                continue
            }
        }
        return false
    }
}
