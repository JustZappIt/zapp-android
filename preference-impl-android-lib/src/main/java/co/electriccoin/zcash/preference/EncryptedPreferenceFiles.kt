package co.electriccoin.zcash.preference

import android.content.Context
import java.io.File

internal fun sharedPreferencesDirectory(context: Context): File = File(context.filesDir.parent, "shared_prefs")

internal fun encryptedPreferencesFile(
    sharedPrefsDir: File,
    filename: String
): File = File(sharedPrefsDir, "$filename.xml")

internal fun encryptedPreferencesBackupFile(
    sharedPrefsDir: File,
    filename: String
): File = File(sharedPrefsDir, "$filename.xml.bak")

/**
 * Removes `<filename>.xml` and its `.xml.bak` sibling from [sharedPrefsDir]; a missing file is a
 * no-op. Returns whether neither file remains.
 */
internal fun deleteEncryptedPreferencesFiles(
    sharedPrefsDir: File,
    filename: String
): Boolean {
    val xmlFile = encryptedPreferencesFile(sharedPrefsDir, filename)
    val bakFile = encryptedPreferencesBackupFile(sharedPrefsDir, filename)

    xmlFile.delete()
    bakFile.delete()

    return !xmlFile.exists() && !bakFile.exists()
}
