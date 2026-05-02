import sys

file_path = r'c:\Users\user\Desktop\chatapp\app\src\main\java\com\example\chatapp\FreeChatActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    lines = f.readlines()

new_content = """    private fun updateMessagesViewportAnchor() {
        val params = binding.messagesScrollView.layoutParams as ConstraintLayout.LayoutParams
        if (params.bottomToBottom != ConstraintLayout.LayoutParams.PARENT_ID) {
            params.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomMargin = 0
            binding.messagesScrollView.layoutParams = params
        }
        
        val welcomeParams = binding.welcomeScreen.layoutParams as ConstraintLayout.LayoutParams
        if (welcomeParams.bottomToBottom != ConstraintLayout.LayoutParams.PARENT_ID) {
            welcomeParams.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            welcomeParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            binding.welcomeScreen.layoutParams = welcomeParams
        }
        
        val anonWelcomeParams = binding.anonymousWelcomeScreen.layoutParams as ConstraintLayout.LayoutParams
        if (anonWelcomeParams.bottomToBottom != ConstraintLayout.LayoutParams.PARENT_ID) {
            anonWelcomeParams.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            anonWelcomeParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            binding.anonymousWelcomeScreen.layoutParams = anonWelcomeParams
        }
    }

    private fun setupWelcomeActions() {
        binding.btnCreateImage.setHapticClickListener {
            activateImageSuggestions()
            binding.etInput.requestFocus()
        }
        binding.btnIdea.setHapticClickListener {
            activateIdeaSuggestions()
            binding.etInput.requestFocus()
        }
        binding.btnCenterMore.setHapticClickListener {
            BottomSheetMenuFragment().show(supportFragmentManager, "bottom_sheet_menu")
        }
    }

    private fun setupPreview() {
        binding.btnRemovePreview.setOnClickListener {
            clearPreview()
        }
    }

    private fun setupAds() {
        binding.btnAddLimits.isVisible = true
        adManager = RewardedAdManager(this) {
            chatViewModel.addLimits(5) {
                runOnUiThread {
                    refreshDailyQuotaUi()
                    toast(LocaleHelper.getString(this, "toast_limits_added"))
                }
            }
        }
        adManager?.initialize()
    }
"""

lines[1294:1305] = [line + '\n' for line in new_content.split('\n')]

with open(file_path, 'w', encoding='utf-8') as f:
    f.writelines(lines)
