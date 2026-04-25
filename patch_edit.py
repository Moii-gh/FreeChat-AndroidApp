import sys

path = r'c:\Users\user\Desktop\chatapp\app\src\main\java\com\example\chatapp\network\OpenAiDirectService.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Import delay and other utilities if missing
if 'import kotlinx.coroutines.delay' not in content:
    content = content.replace('import kotlinx.coroutines.Dispatchers', 'import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.delay\nimport android.graphics.Bitmap\nimport android.graphics.BitmapFactory\nimport java.io.ByteArrayOutputStream\nimport okhttp3.MultipartBody')

# 2. Add messages parameter to executeToolCall
old_exec_tool = 'private fun executeToolCall(apiKey: String, functionName: String, args: JSONObject): String {'
new_exec_tool = 'private fun executeToolCall(apiKey: String, functionName: String, args: JSONObject, messages: JSONArray): String {'
content = content.replace(old_exec_tool, new_exec_tool)

old_exec_call = 'val toolResult = executeToolCall(apiKey, functionName, args)'
new_exec_call = 'val toolResult = executeToolCall(apiKey, functionName, args, messages)'
content = content.replace(old_exec_call, new_exec_call)

# 3. Update edit_image block
old_edit_block = """            "edit_image" -> {
                val prompt = args.optString("prompt", "")
                "Инструмент edit_image пока не реализован."
            }"""

new_edit_block = """            "edit_image" -> {
                val prompt = args.optString("prompt", "")
                if (prompt.isBlank()) return "Пустое описание для редактирования"
                editImage(apiKey, prompt, messages)
            }"""
content = content.replace(old_edit_block, new_edit_block)

# 4. Update generateImage model
old_dalle3 = 'put("model", "dall-e-3")'
new_dalle3 = 'put("model", "gpt-image-1")'
content = content.replace(old_dalle3, new_dalle3)

# 5. Insert editImage function before generateImage
generate_image_idx = content.find('private fun generateImage')

edit_image_code = """    private fun editImage(apiKey: String, prompt: String, messages: JSONArray): String {
        return try {
            // Ищем последнее изображение в base64
            var base64Image: String? = null
            for (i in messages.length() - 1 downTo 0) {
                val msg = messages.optJSONObject(i)
                if (msg != null && msg.optString("role") == "user") {
                    val contentArr = msg.optJSONArray("content")
                    if (contentArr != null) {
                        for (j in 0 until contentArr.length()) {
                            val part = contentArr.optJSONObject(j)
                            if (part != null && part.optString("type") == "image_url") {
                                val url = part.optJSONObject("image_url")?.optString("url")
                                if (url != null && url.startsWith("data:image")) {
                                    base64Image = url.substringAfter("base64,")
                                    break
                                }
                            }
                        }
                    }
                }
                if (base64Image != null) break
            }

            if (base64Image == null) {
                return "Ошибка: Не найдено исходное изображение для редактирования в последних сообщениях."
            }

            // Декодируем и обрезаем в квадрат 1024x1024
            val imageBytes = android.util.Base64.decode(base64Image, android.util.Base64.DEFAULT)
            var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            
            val minSize = Math.min(bitmap.width, bitmap.height)
            val cropX = (bitmap.width - minSize) / 2
            val cropY = (bitmap.height - minSize) / 2
            val croppedBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, minSize, minSize)
            val scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, 1024, 1024, true)
            
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val pngBytes = outputStream.toByteArray()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "gpt-image-1")
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("n", "1")
                .addFormDataPart("size", "1024x1024")
                .addFormDataPart("image", "image.png", okhttp3.RequestBody.create("image/png".toMediaType(), pngBytes))
                .build()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/images/edits")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = createOpenAiClient().newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val url = JSONObject(responseBody).optJSONArray("data")?.optJSONObject(0)?.optString("url")
                if (url != null) {
                    "Изображение успешно изменено. Выведи пользователю следующий Markdown: ![Отредактированное изображение]($url)"
                } else {
                    "Ошибка: URL изображения не найден в ответе."
                }
            } else {
                "Ошибка при редактировании изображения: $responseBody"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image Edit Error", e)
            "Сетевая ошибка при редактировании изображения: ${e.message}"
        }
    }

"""
content = content[:generate_image_idx] + edit_image_code + content[generate_image_idx:]

# 6. Smooth Generation
old_chunk_loop = """                        finalReply += chunk
                        withContext(Dispatchers.Main) { callback.onChunk(finalReply) }"""

new_chunk_loop = """                        for (c in chunk) {
                            finalReply += c
                            withContext(Dispatchers.Main) { callback.onChunk(finalReply) }
                            delay(10)
                        }"""
content = content.replace(old_chunk_loop, new_chunk_loop)

# 7. Add Log for Title Error
old_title_catch = """                if (!response.isSuccessful) return@runCatching null

                val responseBody = response.body?.string().orEmpty()"""

new_title_catch = """                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "generateTitle error: ${response.code} $responseBody")
                    return@runCatching null
                }"""
content = content.replace(old_title_catch, new_title_catch)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)

print('Patch applied')
