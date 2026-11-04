package com.pulseloop.coach.ondevice

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * The on-device coach's model (docs/on-device-model-and-mcp-plan.md, Part 1), copied from
 * Cochilo's `LocalModel`. One small interface, so the runtime can change (LiteRT-LM after the
 * Kotlin bump) without touching the coach. Everything runs on the phone; nothing is sent anywhere.
 */
interface LocalModel {
    /** The model's continuation of [prompt]. Slow (seconds): call it off the main thread. */
    fun generate(prompt: String): String

    /** How many tokens [text] takes, so a prompt never overflows the model's window. */
    fun tokens(text: String): Int

    fun close()

    companion object {
        /**
         * Prompt and answer together. 1280 is what Cochilo runs this same file with; a prompt past
         * the model's window crashed natively there, so it stays at the proven figure.
         */
        const val MAX_TOKENS = 1280

        /** Room kept for the answer; a prompt may use the rest and is trimmed to fit ([OnDevicePrompt.fit]). */
        const val REPLY_TOKENS = 384
    }
}

/** MediaPipe's LLM Inference with a `.task` model file (Gemma 3 1B int4). */
@Suppress("DEPRECATION") // MediaPipe's LLM Inference is deprecated in favour of LiteRT-LM (a later step).
class MediaPipeModel(context: Context, file: File) : LocalModel {
    private val engine: LlmInference = LlmInference.createFromOptions(
        context,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(LocalModel.MAX_TOKENS)
            // CPU: the GPU path crashes natively on some phones, and a 1B model is quick enough on CPU.
            .setPreferredBackend(LlmInference.Backend.CPU)
            .build(),
    )

    override fun generate(prompt: String): String = engine.generateResponse(prompt)

    override fun tokens(text: String): Int = engine.sizeInTokens(text)

    override fun close() = engine.close()
}
