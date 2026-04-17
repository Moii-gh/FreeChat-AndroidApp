package com.example.chatapp

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class BottomSheetMenuFragment : BottomSheetDialogFragment() {
    private var cameraImageUri: Uri? = null

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            cameraImageUri?.let {
                val activity = activity as? ChatInputHost
                activity?.showFilePreview(it)
            }
        }
        dismiss()
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val activity = activity as? ChatInputHost
            activity?.showFilePreview(it)
            dismiss()
        }
    }

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val activity = activity as? ChatInputHost
            activity?.showFilePreview(it)
            dismiss()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_bottom_sheet, container, false)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as com.google.android.material.bottomsheet.BottomSheetDialog
        dialog.setOnShowListener { dialogInterface ->
            val d = dialogInterface as com.google.android.material.bottomsheet.BottomSheetDialog
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        val activity = activity as? ChatInputHost

        // Translate the UI text elements
        val context = requireContext()
        view.findViewById<android.widget.TextView>(R.id.tvBtnCamera)?.text = LocaleHelper.getString(context, "button_camera")
        view.findViewById<android.widget.TextView>(R.id.tvBtnPhoto)?.text = LocaleHelper.getString(context, "button_photo")
        view.findViewById<android.widget.TextView>(R.id.tvBtnFiles)?.text = LocaleHelper.getString(context, "button_files")
        
        view.findViewById<android.widget.TextView>(R.id.tvOptCreateImageTitle)?.text = LocaleHelper.getString(context, "button_create_image")
        view.findViewById<android.widget.TextView>(R.id.tvOptCreateImageDesc)?.text = LocaleHelper.getString(context, "button_create_image_description")
        
        view.findViewById<android.widget.TextView>(R.id.tvOptSearchTitle)?.text = LocaleHelper.getString(context, "button_Search_the_web")
        view.findViewById<android.widget.TextView>(R.id.tvOptSearchDesc)?.text = LocaleHelper.getString(context, "button_Search_the_web_description")
        
        view.findViewById<android.widget.TextView>(R.id.tvOptShoppingTitle)?.text = LocaleHelper.getString(context, "button _purchase_research")
        view.findViewById<android.widget.TextView>(R.id.tvOptShoppingDesc)?.text = LocaleHelper.getString(context, "button _purchase_research_description")
        
        view.findViewById<android.widget.TextView>(R.id.tvOptStudyTitle)?.text = LocaleHelper.getString(context, "button_stud_ and_training")
        view.findViewById<android.widget.TextView>(R.id.tvOptStudyDesc)?.text = LocaleHelper.getString(context, "button_stud_ and_training_description")

        view.findViewById<View>(R.id.btnCamera).setOnClickListener {
            val context = requireContext()
            val file = java.io.File(context.cacheDir, "camera_image_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            cameraImageUri = uri
            takePictureLauncher.launch(uri)
        }

        view.findViewById<View>(R.id.btnPhoto).setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        view.findViewById<View>(R.id.btnFiles).setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        view.findViewById<View>(R.id.optCreateImage).setOnClickListener {
            activity?.setInputContext(
                LocaleHelper.getString(requireContext(), "panel_create_image"),
                R.drawable.ic_palette,
                LocaleHelper.getString(requireContext(), "main_panel_input_create_image"),
                "#34C759",
                "create_image"
            )
            dismiss()
        }

        view.findViewById<View>(R.id.optSearch).setOnClickListener {
            activity?.setInputContext(
                LocaleHelper.getString(requireContext(), "panel_search"),
                R.drawable.ic_globe_new,
                LocaleHelper.getString(requireContext(), "main_panel_input_panel_search"),
                "#34C759",
                "search"
            )
            dismiss()
        }

        view.findViewById<View>(R.id.optShopping).setOnClickListener {
            activity?.setInputContext(
                LocaleHelper.getString(requireContext(), "panel_purchase_research"),
                R.drawable.ic_shopping_new,
                LocaleHelper.getString(requireContext(), "main_panel_input_purchase_research"),
                "#34C759",
                "shopping"
            )
            dismiss()
        }

        view.findViewById<View>(R.id.optStudy).setOnClickListener {
            activity?.setInputContext(
                LocaleHelper.getString(requireContext(), "panel_stud_ and_training"),
                R.drawable.ic_book_new,
                LocaleHelper.getString(requireContext(), "main_panel_stud_ and_training"),
                "#34C759",
                "study"
            )
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setDimAmount(0.6f)
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
