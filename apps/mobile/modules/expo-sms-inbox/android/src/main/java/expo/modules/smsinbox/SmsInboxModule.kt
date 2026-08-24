package expo.modules.smsinbox

import android.Manifest
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.Telephony
import androidx.core.content.ContextCompat
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * Reads bank alerts out of the Android message store.
 *
 * Written as a local module rather than pulling in one of the published SMS
 * packages. Those predate the New Architecture and are largely unmaintained,
 * and for something holding the `READ_SMS` permission the ability to read every
 * line of it matters more than the few hours it saves.
 *
 * ## What this deliberately does not do
 *
 * It returns three columns: sender, body and timestamp. Not the thread id, not
 * the contact, not the read flag, not the message id. The narrower the bridge,
 * the less there is for a mistake in the JavaScript above to leak — and none of
 * the omitted fields would improve the parsing.
 *
 * It also reads only [Telephony.Sms.Inbox]. Sent messages are conversation by
 * definition and contain no bank alerts.
 *
 * No filtering happens here. Deciding which messages are payments is done in
 * TypeScript, where it is shared with the server implementation and covered by
 * the same corpus of examples; a second copy of those rules in Kotlin would be
 * a third thing to keep in step.
 */
class SmsInboxModule : Module() {

  private val context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  override fun definition() = ModuleDefinition {
    Name("ExpoSmsInbox")

    AsyncFunction("hasPermission") {
      ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED
    }

    AsyncFunction("requestPermission") { promise: Promise ->
      Permissions.askForPermissionsWithPermissionsManager(
        appContext.permissions,
        promise,
        Manifest.permission.READ_SMS
      )
    }

    AsyncFunction("readInbox") { since: Double, limit: Int ->
      // Milliseconds arrive as a Double because JavaScript has no 64-bit
      // integer. The value is well inside the range a Double represents
      // exactly, so nothing is lost -- but it must be converted before it
      // reaches SQLite, which would otherwise be handed "1.7707392E12".
      readInbox(since.toLong(), limit)
    }
  }

  private fun readInbox(sinceMillis: Long, limit: Int): List<Map<String, Any?>> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      // Returning empty rather than throwing. Permission can be revoked from
      // Android settings while the app is backgrounded, so this is a state the
      // app must handle calmly on a routine refresh, not an exceptional one.
      return emptyList()
    }

    val projection = arrayOf(
      Telephony.Sms.ADDRESS,
      Telephony.Sms.BODY,
      Telephony.Sms.DATE
    )

    val cursor: Cursor = context.contentResolver.query(
      Telephony.Sms.Inbox.CONTENT_URI,
      projection,
      "${Telephony.Sms.DATE} >= ?",
      arrayOf(sinceMillis.toString()),
      // Newest first, so that a limit truncates the oldest. Recent spending is
      // what the app is for, and anything cut off is still in the inbox for a
      // later pass to collect.
      "${Telephony.Sms.DATE} DESC LIMIT ${limit.coerceIn(1, MAX_ROWS)}"
    ) ?: return emptyList()

    return cursor.use { rows ->
      val address = rows.getColumnIndex(Telephony.Sms.ADDRESS)
      val body = rows.getColumnIndex(Telephony.Sms.BODY)
      val date = rows.getColumnIndex(Telephony.Sms.DATE)

      val results = ArrayList<Map<String, Any?>>(rows.count)
      while (rows.moveToNext()) {
        results.add(
          mapOf(
            "address" to if (address >= 0) rows.getString(address).orEmpty() else "",
            "body" to if (body >= 0) rows.getString(body).orEmpty() else "",
            // The stored timestamp, never System.currentTimeMillis(). The
            // server folds this into the fingerprint that collapses duplicates,
            // so substituting the clock here would give the same message a new
            // identity on every scan and multiply it in the user's records.
            "date" to if (date >= 0) rows.getLong(date) else 0L
          )
        )
      }
      results
    }
  }

  private companion object {
    /**
     * A hard ceiling on one bridge crossing.
     *
     * An inbox with tens of thousands of messages would otherwise be
     * serialised into a single array and take the app down on the phones least
     * able to spare the memory. Callers page by moving `since` forward.
     */
    const val MAX_ROWS = 2_000
  }
}
