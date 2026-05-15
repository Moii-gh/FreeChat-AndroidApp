package com.example.chatapp.ui

import android.content.Context
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.ai.AiActivityState

internal object AiActivityStatusPresenter {
    fun text(context: Context, state: AiActivityState): String =
        when (state) {
            AiActivityState.Thinking -> LocaleHelper.getString(context, "ai_activity_reasoning")
            AiActivityState.WebSearching -> LocaleHelper.getString(context, "ai_activity_web_searching")
            AiActivityState.GeneratingImage -> LocaleHelper.getString(context, "ai_activity_generating_image")
            AiActivityState.EditingImage -> LocaleHelper.getString(context, "ai_activity_editing_image")
            AiActivityState.AnalyzingImage -> LocaleHelper.getString(context, "ai_activity_analyzing_image")
            AiActivityState.ExecutingCode -> LocaleHelper.getString(context, "ai_activity_executing_code")
            AiActivityState.ReadingFiles -> LocaleHelper.getString(context, "ai_activity_reading_files")
            AiActivityState.LookingUpMemory -> LocaleHelper.getString(context, "ai_activity_memory_lookup")
            AiActivityState.Translating -> LocaleHelper.getString(context, "ai_activity_translating")
            AiActivityState.Summarizing -> LocaleHelper.getString(context, "ai_activity_summarizing")
            AiActivityState.TranscribingAudio -> LocaleHelper.getString(context, "ai_activity_transcribing_audio")
            AiActivityState.ProcessingRequest -> LocaleHelper.getString(context, "ai_activity_processing_request")
            AiActivityState.AnalyzingSearchResults -> LocaleHelper.getString(context, "ai_activity_analyzing_results")
            AiActivityState.FormingAnswer -> LocaleHelper.getString(context, "ai_activity_forming_answer")
            is AiActivityState.Custom -> state.text
        }

    fun iconRes(state: AiActivityState): Int =
        when (state) {
            AiActivityState.WebSearching,
            AiActivityState.AnalyzingSearchResults -> R.drawable.ic_globe_new
            AiActivityState.GeneratingImage,
            AiActivityState.EditingImage -> R.drawable.ic_image
            AiActivityState.AnalyzingImage -> R.drawable.ic_camera
            AiActivityState.ExecutingCode -> R.drawable.ic_file_new
            AiActivityState.ReadingFiles -> R.drawable.ic_file_new
            AiActivityState.LookingUpMemory,
            AiActivityState.Thinking -> R.drawable.ic_brain
            AiActivityState.Translating -> R.drawable.ic_language
            AiActivityState.Summarizing,
            AiActivityState.FormingAnswer -> R.drawable.ic_idea
            AiActivityState.TranscribingAudio -> R.drawable.ic_mic
            AiActivityState.ProcessingRequest,
            is AiActivityState.Custom -> R.drawable.ic_idea
        }
}
