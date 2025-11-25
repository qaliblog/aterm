package com.qali.aterm.autogent

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

/**
 * CodeBERT classifier using ONNX Runtime
 * Loads ONNX model and performs inference
 */
class CodeBertClassifier(
    context: Context,
    modelPath: String,
    vocabPath: String,
    mergesPath: String
) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer: CodeBertTokenizer
    
    init {
        // Load ONNX model
        session = env.createSession(modelPath)
        
        // Initialize tokenizer
        tokenizer = CodeBertTokenizer(vocabPath, mergesPath)
    }
    
    /**
     * Classify text and return logits
     */
    fun classify(text: String): FloatArray {
        // Tokenize input
        val tokens = tokenizer.encode(text)
        
        // Create input tensor with shape [1, sequence_length]
        // ONNX Runtime API: createTensor(env, data) where data is the array
        // For 2D shape [1, sequence_length], we create Array<LongArray>
        // Note: LongArray in Kotlin is compatible with Java long[]
        val tokens2D = arrayOf(tokens)
        val inputTensor = OnnxTensor.createTensor(env, tokens2D)
        
        var output: OrtSession.Result? = null
        try {
            // Run inference
            val inputs = mapOf("input_ids" to inputTensor)
            output = session.run(inputs)
            
            // Extract logits from output
            // Output shape is typically [batch_size, sequence_length, vocab_size] or [batch_size, vocab_size]
            val outputTensor = output[0]
            val outputValue = outputTensor.value
            
            return when (outputValue) {
                is Array<*> -> {
                    // Handle different output shapes
                    when (val first = outputValue[0]) {
                        is FloatArray -> first
                        is Array<*> -> {
                            // Flatten if needed - handle nested arrays
                            when (first) {
                                is FloatArray -> first
                                is Array<*> -> {
                                    // Try to get first element if it's a nested array
                                    (first as? Array<FloatArray>)?.getOrNull(0) ?: FloatArray(0)
                                }
                                else -> FloatArray(0)
                            }
                        }
                        else -> FloatArray(0)
                    }
                }
                is FloatArray -> outputValue
                else -> FloatArray(0)
            }
        } finally {
            // Clean up
            inputTensor.close()
            output?.close()
        }
    }
    
    /**
     * Get classification result with probabilities
     */
    fun classifyWithProbabilities(text: String): Pair<Int, FloatArray> {
        val logits = classify(text)
        
        // Apply softmax to get probabilities
        val probabilities = softmax(logits)
        
        // Get predicted class (index with highest probability)
        val predictedClass = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
        
        return Pair(predictedClass, probabilities)
    }
    
    /**
     * Apply softmax to logits
     */
    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.maxOrNull() ?: 0f
        val expLogits = logits.map { kotlin.math.exp(it - maxLogit) }
        val sumExp = expLogits.sum()
        return expLogits.map { (it / sumExp).toFloat() }.toFloatArray()
    }
    
    /**
     * Close resources
     */
    fun close() {
        session.close()
    }
}
