package com.qali.aterm.agent.core

import com.google.gson.annotations.SerializedName

/**
 * Represents a content part in a message
 */
sealed class Part {
    data class TextPart(
        val text: String,
        val thought: String? = null
    ) : Part()
    
    data class FunctionCallPart(
        val functionCall: FunctionCall
    ) : Part()
    
    data class FunctionResponsePart(
        val functionResponse: FunctionResponse
    ) : Part()
}

data class FunctionCall(
    val name: String,
    val args: Map<String, Any>,
    val id: String? = null // Optional call ID from API
)

data class FunctionResponse(
    val name: String,
    val response: Map<String, Any>,
    val id: String? = null // Call ID for tracking
)

/**
 * Represents a message in the conversation
 */
data class Content(
    val role: String, // "user" or "model"
    val parts: List<Part>
)

/**
 * Tool function declaration for Gemini API
 */
data class FunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: FunctionParameters
)

data class FunctionParameters(
    val type: String = "object",
    val properties: Map<String, PropertySchema> = emptyMap(),
    val required: List<String> = emptyList()
)

data class PropertySchema(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
    val items: PropertySchema? = null
)

data class Tool(
    @SerializedName("functionDeclarations")
    val functionDeclarations: List<FunctionDeclaration>
)

/**
 * GenerateContentConfig for API calls
 */
data class GenerateContentConfig(
    val temperature: Double = 1.0,
    val topP: Double = 0.95,
    val topK: Int = 64,
    val systemInstruction: String? = null,
    val tools: List<Tool>? = null,
    val thinkingConfig: ThinkingConfig? = null
)

data class ThinkingConfig(
    val includeThoughts: Boolean = true,
    val thinkingBudget: Int = 1000000
)

/**
 * Response from Gemini API
 */
data class GenerateContentResponse(
    val candidates: List<Candidate>? = null,
    val finishReason: String? = null,
    val usageMetadata: UsageMetadata? = null
)

data class Candidate(
    val content: Content? = null,
    val finishReason: String? = null
)

data class UsageMetadata(
    val promptTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val totalTokenCount: Int? = null
)

/**
 * Grounding metadata for web search
 */
data class GroundingMetadata(
    val groundingChunks: List<GroundingChunkItem>? = null,
    val groundingSupports: List<GroundingSupportItem>? = null
)

data class GroundingChunkItem(
    val web: GroundingChunkWeb? = null
)

data class GroundingChunkWeb(
    val uri: String? = null,
    val title: String? = null
)

data class GroundingSupportItem(
    val segment: GroundingSupportSegment? = null,
    val groundingChunkIndices: List<Int>? = null,
    val confidenceScores: List<Double>? = null
)

data class GroundingSupportSegment(
    val startIndex: Int,
    val endIndex: Int,
    val text: String? = null
)

/**
 * Extended GenerateContentResponse with grounding metadata
 */
data class GenerateContentResponseWithGrounding(
    val candidates: List<CandidateWithGrounding>? = null,
    val finishReason: String? = null,
    val usageMetadata: UsageMetadata? = null
)

data class CandidateWithGrounding(
    val content: Content? = null,
    val finishReason: String? = null,
    val groundingMetadata: GroundingMetadata? = null
)
