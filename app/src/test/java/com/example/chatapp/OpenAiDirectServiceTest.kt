package com.example.chatapp

import com.example.chatapp.network.OpenAiDirectService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiDirectServiceTest {

    @Test
    fun `request history drops previous tool protocol messages`() {
        val messages = OpenAiDirectService.buildMessagesArray(
            systemPrompt = "system",
            messagesToKeep = listOf(
                JSONObject().apply {
                    put("role", "user")
                    put("content", "Find current news")
                },
                JSONObject().apply {
                    put("role", "assistant")
                    put("content", JSONObject.NULL)
                    put("tool_calls", JSONArray().put(JSONObject().apply {
                        put("id", "call_1")
                    }))
                },
                JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", "call_1")
                    put("content", "Search results")
                },
                JSONObject().apply {
                    put("role", "assistant")
                    put("content", "Final answer")
                },
                JSONObject().apply {
                    put("role", "user")
                    put("content", "Follow-up question")
                }
            )
        )

        assertEquals(4, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
        assertEquals("user", messages.getJSONObject(3).getString("role"))
        assertFalse(messages.toString().contains("tool_calls"))
        assertFalse(messages.toString().contains("tool_call_id"))
    }

    @Test
    fun `assistant generated image history is sent as plain text`() {
        val messages = OpenAiDirectService.buildMessagesArray(
            systemPrompt = "system",
            messagesToKeep = listOf(
                JSONObject().apply {
                    put("role", "assistant")
                    put("content", "![image](data:image/png;base64,aW1hZ2U=)")
                    put("base64", "aW1hZ2U=")
                    put("mimeType", "image/png")
                }
            )
        )

        val assistantMessage = messages.getJSONObject(1)
        assertEquals("assistant", assistantMessage.getString("role"))
        assertTrue(assistantMessage.get("content") is String)
        assertEquals("[Generated image]", assistantMessage.getString("content"))
    }

    @Test
    fun `screen assistant image is sent as openai image url content`() {
        val messages = OpenAiDirectService.buildMessagesArray(
            systemPrompt = "system",
            messagesToKeep = listOf(
                JSONObject().apply {
                    put("role", "user")
                    put("content", "What is shown on the screen? Help the user understand it.")
                    put("base64", "c2NyZWVu")
                    put("mimeType", "image/jpeg")
                    put("fileName", "screen.jpg")
                }
            )
        )

        val userContent = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("text", userContent.getJSONObject(0).getString("type"))
        assertEquals("image_url", userContent.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,c2NyZWVu",
            userContent.getJSONObject(1).getJSONObject("image_url").getString("url")
        )
    }

    @Test
    fun `openai web search request uses responses built in web search tool`() {
        val body = OpenAiDirectService.buildResponsesWebSearchRequestBody(
            systemPrompt = "system",
            messagesToKeep = listOf(
                JSONObject().apply {
                    put("role", "user")
                    put("content", "Find current news")
                }
            )
        )

        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("web_search", tool.getString("type"))
        assertEquals("required", body.getString("tool_choice"))
        assertEquals(
            "web_search_call.action.sources",
            body.getJSONArray("include").getString(0)
        )
        assertFalse(body.toString().contains("\"function\""))
    }

    @Test
    fun `openai file search request uses responses built in file search tool`() {
        val body = OpenAiDirectService.buildResponsesFileSearchRequestBody(
            systemPrompt = "system",
            messagesToKeep = listOf(
                JSONObject().apply {
                    put("role", "user")
                    put("content", "Analyze this file")
                    put("fileName", "report.pdf")
                    put("fileContext", "Local extracted preview should not be sent")
                    put("mimeType", "application/pdf")
                    put("base64", "JVBERi0=")
                }
            ),
            vectorStoreId = "vs_test",
            includeWebSearch = false
        )

        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("file_search", tool.getString("type"))
        assertEquals("vs_test", tool.getJSONArray("vector_store_ids").getString(0))
        assertEquals("required", body.getString("tool_choice"))
        assertEquals("file_search_call.results", body.getJSONArray("include").getString(0))
        assertTrue(body.getString("instructions").contains("File Search"))
        assertFalse(body.toString().contains("Local extracted preview should not be sent"))
    }

    @Test
    fun `openai web search sources are appended from annotations and source list`() {
        val response = JSONObject().apply {
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "web_search_call")
                    put("action", JSONObject().apply {
                        put("sources", JSONArray().put(JSONObject().apply {
                            put("title", "Second source")
                            put("url", "https://example.com/second")
                        }))
                    })
                })
                put(JSONObject().apply {
                    put("type", "message")
                    put("content", JSONArray().put(JSONObject().apply {
                        put("type", "output_text")
                        put("text", "Answer")
                        put("annotations", JSONArray().put(JSONObject().apply {
                            put("type", "url_citation")
                            put("title", "First source")
                            put("url", "https://example.com/first")
                        }))
                    }))
                })
            })
        }

        val text = OpenAiDirectService.extractResponsesFinalText(response)

        assertTrue(text.contains("Answer"))
        assertTrue(text.contains("[First source](https://example.com/first)"))
        assertTrue(text.contains("[Second source](https://example.com/second)"))
    }

    @Test
    fun `openai file search citations are appended from annotations`() {
        val response = JSONObject().apply {
            put("output", JSONArray().put(JSONObject().apply {
                put("type", "message")
                put("content", JSONArray().put(JSONObject().apply {
                    put("type", "output_text")
                    put("text", "Answer from file")
                    put("annotations", JSONArray().put(JSONObject().apply {
                        put("type", "file_citation")
                        put("file_id", "file_1")
                        put("filename", "report.pdf")
                    }))
                }))
            }))
        }

        val text = OpenAiDirectService.extractResponsesFinalText(response)

        assertTrue(text.contains("Answer from file"))
        assertTrue(text.contains("Файлы:\n1. report.pdf"))
    }

    @Test
    fun `openai web search is selected for search prompts but not image mode`() {
        val searchMessage = listOf(
            JSONObject().apply {
                put("role", "user")
                put("content", "Найди актуальные новости")
            }
        )

        assertTrue(OpenAiDirectService.shouldUseOpenAiWebSearch("search", searchMessage))
        assertTrue(OpenAiDirectService.shouldUseOpenAiWebSearch(null, searchMessage))
        assertFalse(OpenAiDirectService.shouldUseOpenAiWebSearch("create_image", searchMessage))
    }

    @Test
    fun `openai image mode creates a new image when user asks to create`() {
        val messages = listOf(
            JSONObject().apply {
                put("role", "assistant")
                put("content", "![image](data:image/png;base64,aW1hZ2U=)")
            },
            JSONObject().apply {
                put("role", "user")
                put("content", "Create image of a cat in a space suit")
            }
        )

        assertFalse(OpenAiDirectService.shouldUseOpenAiImageEdit("create_image", messages))
    }

    @Test
    fun `openai image edit is selected for explicit edit request`() {
        val messages = listOf(
            JSONObject().apply {
                put("role", "assistant")
                put("content", "![image](data:image/png;base64,aW1hZ2U=)")
            },
            JSONObject().apply {
                put("role", "user")
                put("content", "Change the background to sunset")
            }
        )

        assertTrue(OpenAiDirectService.shouldUseOpenAiImageEdit(null, messages))
    }

    @Test
    fun `openai image response with base64 is converted to data url markdown`() {
        val responseBody = JSONObject().apply {
            put("data", JSONArray().put(JSONObject().apply {
                put("b64_json", "aW1h Z2U=\n")
                put("output_format", "png")
            }))
        }.toString()

        val markdown = OpenAiDirectService.imageMarkdownFromOpenAiResponse(
            responseBody = responseBody,
            altText = "Image",
            successText = "Done"
        )

        assertEquals("Done\n\n![Image](data:image/png;base64,aW1hZ2U=)", markdown)
    }
}
