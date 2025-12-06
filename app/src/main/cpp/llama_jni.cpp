#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global state
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static bool model_loaded = false;

// Default generation parameters
static const int DEFAULT_N_CTX = 2048;
static const int DEFAULT_N_THREADS = 4;
static const int DEFAULT_N_PREDICT = 128; // Reduced from 256 to prevent context overflow
static const float DEFAULT_TEMP = 0.7f;
static const float DEFAULT_TOP_P = 0.9f;
static const float DEFAULT_REPEAT_PENALTY = 1.1f;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_qali_aterm_llm_LocalLlamaModel_loadModelNative(JNIEnv *env, jobject thiz, jstring path) {
    // Unload existing model if any
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    model_loaded = false;
    
    const char *path_str = env->GetStringUTFChars(path, nullptr);
    if (path_str == nullptr) {
        LOGE("Failed to get path string");
        return JNI_FALSE;
    }
    
    LOGI("Loading model from: %s", path_str);
    
    // Initialize model parameters
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only for now
    
    // Load model
    g_model = llama_model_load_from_file(path_str, model_params);
    if (g_model == nullptr) {
        LOGE("Failed to load model from: %s", path_str);
        env->ReleaseStringUTFChars(path, path_str);
        return JNI_FALSE;
    }
    
    LOGI("Model loaded successfully");
    
    // Initialize context parameters
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = DEFAULT_N_CTX;
    ctx_params.n_threads = DEFAULT_N_THREADS;
    ctx_params.n_threads_batch = DEFAULT_N_THREADS;
    
    // Create context
    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (g_ctx == nullptr) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(path, path_str);
        return JNI_FALSE;
    }
    
    LOGI("Context created successfully (n_ctx=%d, n_threads=%d)", DEFAULT_N_CTX, DEFAULT_N_THREADS);
    model_loaded = true;
    
    env->ReleaseStringUTFChars(path, path_str);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qali_aterm_llm_LocalLlamaModel_generateNative(JNIEnv *env, jobject thiz, jstring prompt, jint maxResponseLength) {
    if (!model_loaded || g_ctx == nullptr || g_model == nullptr) {
        LOGE("Model not loaded");
        return env->NewStringUTF("Error: Model not loaded. Please load a model first.");
    }
    
    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_str == nullptr) {
        return env->NewStringUTF("Error: Failed to get prompt");
    }
    
    LOGI("Generating response for prompt: %s", prompt_str);
    
    std::string response;
    
    try {
        // Initialize sampler chain
        auto sparams = llama_sampler_chain_default_params();
        llama_sampler * smpl = llama_sampler_chain_init(sparams);
        
        // Add samplers: top_k -> top_p -> temp -> greedy
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(DEFAULT_TOP_P, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(DEFAULT_TEMP));
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        
        // Get vocab from model
        const llama_vocab * vocab = llama_model_get_vocab(g_model);
        
        // Tokenize prompt
        std::vector<llama_token> tokens;
        int n_tokens = llama_tokenize(vocab, prompt_str, strlen(prompt_str), nullptr, 0, true, false);
        if (n_tokens < 0) {
            n_tokens = -n_tokens;
        }
        tokens.resize(n_tokens);
        llama_tokenize(vocab, prompt_str, strlen(prompt_str), tokens.data(), tokens.size(), true, false);
        
        if (tokens.empty()) {
            LOGE("Failed to tokenize prompt");
            llama_sampler_free(smpl);
            env->ReleaseStringUTFChars(prompt, prompt_str);
            return env->NewStringUTF("Error: Failed to tokenize prompt");
        }
        
        LOGI("Tokenized prompt into %zu tokens", tokens.size());
        
        // Clear memory/KV cache before starting new generation
        llama_memory_clear(llama_get_memory(g_ctx), true);
        
        // Create batch for prompt using helper function
        llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
        
        // Evaluate prompt
        int decode_result = llama_decode(g_ctx, batch);
        if (decode_result != 0) {
            if (decode_result == 1) {
                LOGE("Context full - cannot find KV slot for prompt (error code: %d)", decode_result);
                llama_sampler_free(smpl);
                env->ReleaseStringUTFChars(prompt, prompt_str);
                return env->NewStringUTF("Error: Context is full. Prompt is too long. Please reduce the prompt size or increase context window.");
            } else {
                LOGE("Failed to evaluate prompt, error code: %d", decode_result);
                llama_sampler_free(smpl);
                env->ReleaseStringUTFChars(prompt, prompt_str);
                return env->NewStringUTF("Error: Failed to evaluate prompt");
            }
        }
        
        // Generate tokens
        int n_cur = tokens.size();
        // Limit prediction to prevent context overflow - ensure we don't exceed context size
        int max_available = DEFAULT_N_CTX - tokens.size() - 10; // Leave some buffer
        int n_predict = (DEFAULT_N_PREDICT < max_available) ? DEFAULT_N_PREDICT : max_available;
        if (n_predict <= 0) {
            LOGE("No room for generation - prompt too long (tokens: %zu, ctx: %d)", tokens.size(), DEFAULT_N_CTX);
            llama_sampler_free(smpl);
            env->ReleaseStringUTFChars(prompt, prompt_str);
            return env->NewStringUTF("Error: Prompt is too long. No room for generation. Please reduce the prompt size.");
        }
        llama_token eos_token = llama_vocab_eos(vocab);
        
        // Enhanced repetition detection
        std::string last_30_chars = "";
        std::string last_20_chars = "";
        int repetition_count_30 = 0;
        int repetition_count_20 = 0;
        const int MAX_REPETITION = 2; // Reduced from 3
        // Use provided max length, or default to 800 for chat, 8000 for code/blueprint
        const size_t MAX_RESPONSE_LENGTH = (maxResponseLength > 0) ? (size_t)maxResponseLength : 800;
        const int MAX_REPEATED_PHRASES = 4; // Reduced from 5
        
        while (n_cur < tokens.size() + n_predict) {
            // Sample next token (idx is the logits position, -1 means last)
            llama_token new_token_id = llama_sampler_sample(smpl, g_ctx, -1);
            
            // Check for EOS or EOG (end of generation)
            if (new_token_id == eos_token || llama_vocab_is_eog(vocab, new_token_id)) {
                LOGI("EOS/EOG token generated");
                break;
            }
            
            // Decode and append to response
            char buf[256];
            int n_chars = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, false);
            if (n_chars > 0 && n_chars < (int)sizeof(buf)) {
                buf[n_chars] = '\0';
                response += buf;
                
                // Enhanced repetition detection: check multiple window sizes
                if (response.length() >= 30) {
                    std::string current_30 = response.substr(response.length() - 30);
                    if (current_30 == last_30_chars) {
                        repetition_count_30++;
                        if (repetition_count_30 >= MAX_REPETITION) {
                            LOGI("Repetition detected (30 chars), stopping generation");
                            break;
                        }
                    } else {
                        repetition_count_30 = 0;
                    }
                    last_30_chars = current_30;
                }
                
                if (response.length() >= 20) {
                    std::string current_20 = response.substr(response.length() - 20);
                    if (current_20 == last_20_chars) {
                        repetition_count_20++;
                        if (repetition_count_20 >= MAX_REPETITION + 1) {
                            LOGI("Repetition detected (20 chars), stopping generation");
                            break;
                        }
                    } else {
                        repetition_count_20 = 0;
                    }
                    last_20_chars = current_20;
                }
                
                // Check for repeated phrases in the entire response
                if (response.length() > 100) {
                    // Check if last 15 characters appear too many times
                    std::string last_15 = response.substr(response.length() - 15);
                    int phrase_count = 0;
                    size_t pos = 0;
                    while ((pos = response.find(last_15, pos)) != std::string::npos) {
                        phrase_count++;
                        pos += last_15.length();
                    }
                    if (phrase_count > MAX_REPEATED_PHRASES) {
                        LOGI("Repeated phrase detected (%d times), stopping generation", phrase_count);
                        break;
                    }
                }
            }
            
            // Create batch for new token using helper function
            llama_batch batch_new = llama_batch_get_one(&new_token_id, 1);
            
            // Evaluate this new token
            int decode_result = llama_decode(g_ctx, batch_new);
            if (decode_result != 0) {
                if (decode_result == 1) {
                    // Context full - stop generation gracefully
                    LOGI("Context full during generation (error code: 1), stopping");
                    break;
                } else if (decode_result == 2) {
                    // Aborted - stop generation
                    LOGI("Generation aborted (error code: 2), stopping");
                    break;
                } else {
                    LOGE("Failed to evaluate token, error code: %d", decode_result);
                    break;
                }
            }
            llama_sampler_accept(smpl, new_token_id);
            
            n_cur++;
            
            // Check if we should stop (response length limit)
            if (response.length() > MAX_RESPONSE_LENGTH) {
                LOGI("Response length limit reached");
                break;
            }
        }
        
        llama_sampler_free(smpl);
        LOGI("Generated response, length: %zu", response.length());
        
    } catch (const std::exception& e) {
        LOGE("Exception during generation: %s", e.what());
        response = "Error: Exception during generation: ";
        response += e.what();
    }
    
    env->ReleaseStringUTFChars(prompt, prompt_str);
    
    if (response.empty()) {
        response = "Error: No response generated";
    }
    
    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_qali_aterm_llm_LocalLlamaModel_unloadModelNative(JNIEnv *env, jobject thiz) {
    LOGI("Unloading model");
    
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    
    model_loaded = false;
    LOGI("Model unloaded");
}
