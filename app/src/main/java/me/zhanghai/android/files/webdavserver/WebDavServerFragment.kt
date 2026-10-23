package me.zhanghai.android.files.webdavserver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import me.zhanghai.android.files.databinding.WebDavServerFragmentBinding

class WebDavServerFragment : Fragment() {
    private lateinit var binding: WebDavServerFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        WebDavServerFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
    }
}
