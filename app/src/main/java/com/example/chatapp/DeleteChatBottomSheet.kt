package com.example.chatapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class DeleteChatBottomSheet : BottomSheetDialogFragment() {

    var onDeleteConfirmed: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_delete, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<android.widget.TextView>(R.id.tvBtnDeleteChat)?.text = LocaleHelper.getString(requireContext(), "button_delete")

        view.findViewById<View>(R.id.btnDeleteChat).setOnClickListener {
            onDeleteConfirmed?.invoke()
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setDimAmount(0.6f)
    }
}
