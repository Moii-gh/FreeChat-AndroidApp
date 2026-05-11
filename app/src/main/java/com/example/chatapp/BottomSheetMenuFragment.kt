package com.example.chatapp

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.example.chatapp.network.AiCapabilities
import com.example.chatapp.network.AiModelCatalog
import com.example.chatapp.network.AiProviderSettings
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.example.chatapp.camera.PremiumCameraActivity
import com.example.chatapp.util.setHapticClickListener

class BottomSheetMenuFragment : BottomSheetDialogFragment() {
    private var cameraImageUri: Uri? = null
    private val serverFeatureUnavailableMessage =
        "\u042d\u0442\u0430 \u0444\u0443\u043d\u043a\u0446\u0438\u044f \u043f\u043e\u043a\u0430 \u043d\u0435 \u043f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u0430 \u043d\u0430 \u0441\u0435\u0440\u0432\u0435\u0440\u0435."

    private val premiumCameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val resultUri = result.data
                ?.getStringExtra(PremiumCameraActivity.EXTRA_RESULT_URI)
                ?.let(Uri::parse)
                ?: cameraImageUri
            resultUri?.let {
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

        // Обновляем тексты нижнего меню под текущую локаль.
        val context = requireContext()
        view.findViewById<android.widget.TextView>(R.id.tvBtnCamera)?.text = LocaleHelper.getString(context, "button_camera")
        view.findViewById<android.widget.TextView>(R.id.tvBtnPhoto)?.text = LocaleHelper.getString(context, "button_photo")
        view.findViewById<android.widget.TextView>(R.id.tvBtnFiles)?.text = LocaleHelper.getString(context, "button_files")
        
        view.findViewById<android.widget.TextView>(R.id.tvOptCreateImageTitle)?.text = LocaleHelper.getString(context, "button_create_image")
        view.findViewById<android.widget.TextView>(R.id.tvOptCreateImageDesc)?.text = LocaleHelper.getString(context, "button_create_image_description")
        
        view.findViewById<android.widget.TextView>(R.id.tvOptSearchTitle)?.text = LocaleHelper.getString(context, "button_search_web")
        view.findViewById<android.widget.TextView>(R.id.tvOptSearchDesc)?.text = LocaleHelper.getString(context, "button_search_web_description")
        
        view.findViewById<android.widget.TextView>(R.id.tvOptShoppingTitle)?.text = LocaleHelper.getString(context, "button_purchase_research")
        view.findViewById<android.widget.TextView>(R.id.tvOptShoppingDesc)?.text = LocaleHelper.getString(context, "button_purchase_research_description")
        
        view.findViewById<android.widget.TextView>(R.id.tvOptStudyTitle)?.text = LocaleHelper.getString(context, "button_study_training")
        view.findViewById<android.widget.TextView>(R.id.tvOptStudyDesc)?.text = LocaleHelper.getString(context, "button_study_training_description")

        view.findViewById<View>(R.id.btnCamera).setHapticClickListener {
            val context = requireContext()
            val file = java.io.File(context.cacheDir, "camera_image_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            cameraImageUri = uri
            premiumCameraLauncher.launch(PremiumCameraActivity.newIntent(context, uri, file))
        }

        view.findViewById<View>(R.id.btnPhoto).setHapticClickListener {
            imagePickerLauncher.launch("image/*")
        }

        view.findViewById<View>(R.id.btnFiles).setHapticClickListener {
            filePickerLauncher.launch("*/*")
        }

        val providerSettings = AiProviderSettings(requireContext())
        val selectedProvider = providerSettings.getProvider()
        val selectedModelKey = providerSettings.getModelKey()
        val canCreateImages = AiModelCatalog.supports(
            selectedProvider,
            selectedModelKey,
            AiCapabilities.IMAGE_GENERATION
        )
        val canUseWebSearch = AiModelCatalog.supports(
            selectedProvider,
            selectedModelKey,
            AiCapabilities.WEB_SEARCH
        )

        configureFeatureOption(view.findViewById(R.id.optCreateImage), canCreateImages) {
            activity?.setInputContext(
                LocaleHelper.getString(requireContext(), "panel_create_image"),
                R.drawable.ic_palette,
                LocaleHelper.getString(requireContext(), "main_panel_input_create_image"),
                "#FFFFFF",
                ChatMode.CREATE_IMAGE
            )
            dismiss()
        }

        configureFeatureOption(view.findViewById(R.id.optSearch), canUseWebSearch) {
            activity?.setInputContext(
                LocaleHelper.getString(requireContext(), "panel_search"),
                R.drawable.ic_globe_new,
                LocaleHelper.getString(requireContext(), "main_panel_input_panel_search"),
                "#FFFFFF",
                ChatMode.SEARCH
            )
            dismiss()
        }

        configureFeatureOption(view.findViewById(R.id.optShopping), canUseWebSearch) {
            activity?.setInputContext(
                LocaleHelper.getString(requireContext(), "panel_purchase_research"),
                R.drawable.ic_shopping_new,
                LocaleHelper.getString(requireContext(), "main_panel_input_purchase_research"),
                "#FFFFFF",
                ChatMode.SHOPPING
            )
            dismiss()
        }

        view.findViewById<View>(R.id.optStudy).setHapticClickListener {
            activity?.setInputContext(
                LocaleHelper.getString(requireContext(), "panel_study_training"),
                R.drawable.ic_book_new,
                LocaleHelper.getString(requireContext(), "main_panel_study_training"),
                "#FFFFFF",
                ChatMode.STUDY
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

    private fun configureFeatureOption(view: View, available: Boolean, action: () -> Unit) {
        view.alpha = if (available) 1f else 0.45f
        view.setHapticClickListener {
            if (available) {
                action()
            } else {
                toast(serverFeatureUnavailableMessage)
            }
        }
    }
}
