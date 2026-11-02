/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import me.zhanghai.android.files.settings.AppLanguage
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.night.NightModeHelper

abstract class AppActivity : AppCompatActivity() {
    private var isDelegateCreated = false
    private var clipboardListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun attachBaseContext(newBase: Context) {
        // Reconcile Android's external app-language setting on activity recreation as well as
        // process startup. An empty list means this product's English default on every device.
        AppLanguage.initialize(newBase.applicationContext ?: newBase)
        super.attachBaseContext(newBase)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboardListener?.let { clipboard.removePrimaryClipChangedListener(it) }
        clipboardListener = null
        if (hasFocus) {
            clipboardListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
                if (!window.decorView.hasWindowFocus()) return@OnPrimaryClipChangedListener
                publishVisibleClipboard(clipboard)
            }.also { clipboard.addPrimaryClipChangedListener(it) }
            // A copy made in another app predates this listener. Read it only now,
            // while this activity owns window focus; Android forbids background reads.
            publishVisibleClipboard(clipboard)
        }
    }

    private fun publishVisibleClipboard(clipboard: android.content.ClipboardManager) {
        if (!window.decorView.hasWindowFocus()) return
        val clip = try { clipboard.primaryClip } catch (_: SecurityException) { null } ?: return
        if (clip.description.label == me.zhanghai.android.files.bridge.AutomaticClipboard.REMOTE_LABEL ||
            (android.os.Build.VERSION.SDK_INT >= 24 && clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)) return
        if (clip.itemCount == 1) clip.getItemAt(0).text?.toString()?.let {
            me.zhanghai.android.files.bridge.AutomaticClipboard.copied(it)
        }
    }

    override fun onDestroy() {
        clipboardListener?.let { getSystemService(android.content.ClipboardManager::class.java).removePrimaryClipChangedListener(it) }
        clipboardListener = null
        super.onDestroy()
    }

    override fun getDelegate(): AppCompatDelegate {
        val delegate = super.getDelegate()

        if (!isDelegateCreated) {
            isDelegateCreated = true
            NightModeHelper.apply(this)
        }
        return delegate
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CustomThemeHelper.apply(this)

        super.onCreate(savedInstanceState)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }
}
