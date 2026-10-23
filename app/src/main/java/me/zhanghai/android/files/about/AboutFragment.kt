/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.about

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import me.zhanghai.android.files.databinding.AboutFragmentBinding
import me.zhanghai.android.files.ui.LicensesDialogFragment
import androidx.core.view.isVisible
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.startActivitySafe

class AboutFragment : Fragment() {
    private lateinit var binding: AboutFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        AboutFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        binding.gitHubLayout.setOnClickListener { startActivitySafe(GITHUB_URI.createViewIntent()) }
        binding.licensesLayout.setOnClickListener { LicensesDialogFragment.show(this) }
        binding.authorNameLayout.setOnClickListener {
            startActivitySafe(AUTHOR_GITHUB_URI.createViewIntent())
        }
        binding.authorGitHubLayout.setOnClickListener {
            startActivitySafe(AUTHOR_GITHUB_URI.createViewIntent())
        }
        // Credit where it is due: FileWeave is a fork and keeps the upstream license.
        binding.upstreamLayout.setOnClickListener {
            startActivitySafe(UPSTREAM_URI.createViewIntent())
        }
        // The upstream author's personal links stay out of FileWeave's about page.
        binding.authorTwitterLayout.isVisible = false
    }

    companion object {
        private val GITHUB_URI = Uri.parse("https://github.com/techfanseric/fileweave")
        private val AUTHOR_GITHUB_URI = Uri.parse("https://github.com/techfanseric")
        // Credit where it is due: FileWeave is a fork and keeps the upstream license.
        private val UPSTREAM_URI = Uri.parse("https://github.com/zhanghai/MaterialFiles")
    }
}
