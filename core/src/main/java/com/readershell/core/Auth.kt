package com.readershell.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores the per-app password in EncryptedSharedPreferences. */
class Auth(ctx: Context, namespace: String) {
    private val prefs = EncryptedSharedPreferences.create(
        ctx,
        "readershell_auth_$namespace",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var password: String?
        get() = prefs.getString("password", null)
        set(value) { prefs.edit().putString("password", value).apply() }
}
