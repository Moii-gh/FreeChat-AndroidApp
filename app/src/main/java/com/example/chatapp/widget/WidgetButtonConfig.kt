package com.example.chatapp.widget

import com.example.chatapp.R

internal enum class WidgetButtonAction(
    val storageKey: String,
    val homeAction: String,
    val iconResId: Int,
    val labelResId: Int,
    val contentDescriptionResId: Int
) {
    Camera(
        storageKey = "camera",
        homeAction = HomeWidgetActionActivity.ACTION_CAMERA,
        iconResId = R.drawable.ic_camera_new,
        labelResId = R.string.button_camera,
        contentDescriptionResId = R.string.button_camera
    ),
    Gallery(
        storageKey = "gallery",
        homeAction = HomeWidgetActionActivity.ACTION_GALLERY,
        iconResId = R.drawable.ic_photo_new,
        labelResId = R.string.button_photo,
        contentDescriptionResId = R.string.content_desc_open_gallery
    ),
    Document(
        storageKey = "document",
        homeAction = HomeWidgetActionActivity.ACTION_DOCUMENT,
        iconResId = R.drawable.ic_file_new,
        labelResId = R.string.button_files,
        contentDescriptionResId = R.string.button_files
    ),
    Mic(
        storageKey = "mic",
        homeAction = HomeWidgetActionActivity.ACTION_MIC,
        iconResId = R.drawable.ic_mic,
        labelResId = R.string.content_desc_microphone,
        contentDescriptionResId = R.string.content_desc_microphone
    );

    companion object {
        val defaultOrder: List<WidgetButtonAction> = listOf(Camera, Gallery, Document, Mic)

        fun fromStorageKey(storageKey: String): WidgetButtonAction? {
            return values().firstOrNull { it.storageKey == storageKey }
        }

        fun normalizedOrder(actions: List<WidgetButtonAction>): List<WidgetButtonAction> {
            val unique = actions.distinct()
            return unique + defaultOrder.filterNot(unique::contains)
        }
    }
}

internal enum class WidgetSizeLevel(val storageKey: String, val displayName: String) {
    OneOne("1_1", "1:1"),
    TwoOne("2_1", "2:1"),
    ThreeOne("3_1", "3:1"),
    FourOne("4_1", "4:1"),
    OneTwo("1_2", "1:2"),
    TwoTwo("2_2", "2:2"),
    ThreeTwo("3_2", "3:2"),
    FourTwo("4_2", "4:2");

    companion object {
        fun fromStorageKey(storageKey: String): WidgetSizeLevel? {
            return values().firstOrNull { it.storageKey == storageKey }
        }

        fun fromDimensions(minWidth: Int, minHeight: Int): WidgetSizeLevel {
            val width = minWidth.takeIf { it > 0 } ?: DEFAULT_MIN_WIDTH_DP
            val height = minHeight.takeIf { it > 0 } ?: DEFAULT_MIN_HEIGHT_DP

            return if (height < ONE_ROW_MAX_HEIGHT_DP) {
                when {
                    width <= 128 -> OneOne
                    width < 200 -> TwoOne
                    width < 300 -> ThreeOne
                    else -> FourOne
                }
            } else {
                when {
                    width <= 128 -> OneTwo
                    width < 190 -> TwoTwo
                    width < 300 -> ThreeTwo
                    else -> FourTwo
                }
            }
        }

        private const val DEFAULT_MIN_WIDTH_DP = 300
        private const val DEFAULT_MIN_HEIGHT_DP = 130
        private const val ONE_ROW_MAX_HEIGHT_DP = 116
    }
}
