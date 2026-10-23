package me.zhanghai.android.files.webdavserver

import android.os.Bundle
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.PreferenceFragmentCompat

class WebDavServerPreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.webdav_server)
    }
}
