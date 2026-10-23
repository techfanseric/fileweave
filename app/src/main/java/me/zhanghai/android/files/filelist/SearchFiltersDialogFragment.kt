package me.zhanghai.android.files.filelist

import android.app.DatePickerDialog
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.SearchFiltersDialogBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class SearchFiltersDialogFragment : DialogFragment() {
    private lateinit var binding: SearchFiltersDialogBinding
    private var start = LocalDate.now()
    private var end = LocalDate.now()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = SearchFiltersDialogBinding.inflate(layoutInflater)
        val state = savedInstanceState ?: requireArguments()
        start = state.getString("start")?.let(LocalDate::parse) ?: LocalDate.now()
        end = state.getString("end")?.let(LocalDate::parse) ?: start
        binding.typeSpinner.setSelection(state.getInt("type"))
        binding.extensionEdit.setText(state.getString("extension", ""))
        binding.recursiveCheck.isChecked = state.getBoolean("recursive", true)
        binding.dateFieldSpinner.setSelection(state.getInt("field"))
        binding.dateRangeSpinner.setSelection(state.getInt("preset"))
        val changed = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateFields()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.typeSpinner.onItemSelectedListener = changed
        binding.dateFieldSpinner.onItemSelectedListener = changed
        binding.dateRangeSpinner.onItemSelectedListener = changed
        binding.startDateButton.setOnClickListener { pickDate(start) { start = it; updateFields() } }
        binding.endDateButton.setOnClickListener { pickDate(end) { end = it; updateFields() } }
        updateFields()
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.fw_search_filters).setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.fw_search_reset) { _, _ -> apply(SearchFilters()) }
            .setPositiveButton(R.string.fw_search_apply, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val type = SearchFileType.entries[binding.typeSpinner.selectedItemPosition]
                val extension = binding.extensionEdit.text.toString().trim().trimStart('.')
                if (type == SearchFileType.EXTENSION && (extension.isEmpty() || extension.any { it == '/' || it == '\\' || it.isWhitespace() })) {
                    binding.extensionEdit.error = getString(R.string.fw_search_extension)
                    return@setOnClickListener
                }
                val today = LocalDate.now()
                val dates = when (binding.dateRangeSpinner.selectedItemPosition) {
                    1 -> SearchDateRange(today, today)
                    2 -> SearchDateRange(today.minusDays(1), today.minusDays(1))
                    3 -> SearchDateRange(today.minusDays(6), today)
                    4 -> {
                        if (end.isBefore(start)) { binding.errorText.isVisible = true; return@setOnClickListener }
                        SearchDateRange(start, end)
                    }
                    else -> null
                }
                apply(SearchFilters(type, extension, binding.recursiveCheck.isChecked,
                    SearchDateField.entries[binding.dateFieldSpinner.selectedItemPosition], dates))
                dismiss()
            }
        }
        return dialog
    }

    private fun apply(filters: SearchFilters) = (requireParentFragment() as FileListFragment).applySearchFilters(filters)

    private fun updateFields() {
        binding.extensionEdit.isVisible = binding.typeSpinner.selectedItemPosition == SearchFileType.EXTENSION.ordinal
        binding.creationNote.isVisible = binding.dateFieldSpinner.selectedItemPosition == SearchDateField.CREATED.ordinal
        binding.customDates.isVisible = binding.dateRangeSpinner.selectedItemPosition == 4
        val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        binding.startDateButton.text = "${getString(R.string.fw_search_start)}: ${start.format(format)}"
        binding.endDateButton.text = "${getString(R.string.fw_search_end)}: ${end.format(format)}"
        binding.errorText.isVisible = false
    }

    private fun pickDate(date: LocalDate, accept: (LocalDate) -> Unit) {
        DatePickerDialog(requireContext(), { _, year, month, day -> accept(LocalDate.of(year, month + 1, day)) },
            date.year, date.monthValue - 1, date.dayOfMonth).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveForm(outState)
        super.onSaveInstanceState(outState)
    }

    private fun saveForm(bundle: Bundle) {
        bundle.putInt("type", binding.typeSpinner.selectedItemPosition)
        bundle.putString("extension", binding.extensionEdit.text.toString())
        bundle.putBoolean("recursive", binding.recursiveCheck.isChecked)
        bundle.putInt("field", binding.dateFieldSpinner.selectedItemPosition)
        bundle.putInt("preset", binding.dateRangeSpinner.selectedItemPosition)
        bundle.putString("start", start.toString()); bundle.putString("end", end.toString())
    }

    companion object {
        fun newInstance(filters: SearchFilters) = SearchFiltersDialogFragment().apply {
            arguments = Bundle().apply {
                putInt("type", filters.type.ordinal); putString("extension", filters.extension)
                putBoolean("recursive", filters.recursive); putInt("field", filters.dateField.ordinal)
                putInt("preset", if (filters.dates == null) 0 else 4)
                putString("start", filters.dates?.start?.toString()); putString("end", filters.dates?.end?.toString())
            }
        }
    }
}
