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
static const int DEFAULT_N_PREDICT = 256;
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
        llama_free_model(g_model);
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
    g_model = llama_load_model_from_file(path_str, model_params);
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
    g_ctx = llama_new_context_with_model(g_model, ctx_params);
    if (g_ctx == nullptr) {
        LOGE("Failed to create context");
        llama_free_model(g_model);
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
Java_com_qali_aterm_llm_LocalLlamaModel_generateNative(JNIEnv *env, jobject thiz, jstring prompt) {
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
        
        // Tokenize prompt
        const llama_vocab * vocab = llama_get_vocab(g_model);
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
        
        // Create batch for prompt
        llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
        for (size_t i = 0; i < tokens.size(); i++) {
            batch.token[i] = tokens[i];
            batch.pos[i] = i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (i == tokens.size() - 1);
        }
        batch.n_tokens = tokens.size();
        
        // Evaluate prompt
        if (llama_decode(g_ctx, batch) < 0) {
            LOGE("Failed to evaluate prompt");
            llama_batch_free(batch);
            llama_sampler_free(smpl);
            env->ReleaseStringUTFChars(prompt, prompt_str);
            return env->NewStringUTF("Error: Failed to evaluate prompt");
        }
        
        llama_batch_free(batch);
        
        // Generate tokens
        int n_cur = tokens.size();
        int n_predict = DEFAULT_N_PREDICT;
        llama_token eos_token = llama_token_eos(g_model);
        
        while (n_cur < tokens.size() + n_predict) {
            // Sample next token
            llama_token new_token_id = llama_sampler_sample(smpl, g_ctx, nullptr, -1);
            
            // Check for EOS
            if (new_token_id == eos_token) {
                LOGI("EOS token generated");
                break;
            }
            
            // Decode and append to response
            char buf[256];
            int n_chars = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, false);
            if (n_chars > 0 && n_chars < (int)sizeof(buf)) {
                buf[n_chars] = '\0';
                response += buf;
            }
            
            // Create batch for new token
            llama_batch batch_new = llama_batch_init(1, 0, 1);
            batch_new.token[0] = new_token_id;
            batch_new.pos[0] = n_cur;
            batch_new.n_seq_id[0] = 1;
            batch_new.seq_id[0][0] = 0;
            batch_new.logits[0] = true;
            batch_new.n_tokens = 1;
            
            // Evaluate this new token
            if (llama_decode(g_ctx, batch_new) < 0) {
                LOGE("Failed to evaluate token");
                llama_batch_free(batch_new);
                break;
            }
            
            llama_batch_free(batch_new);
            llama_sampler_accept(smpl, g_ctx, new_token_id, -1);
            
            n_cur++;
            
            // Check if we should stop (simple check)
            if (response.length() > 2048) {
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
        llama_free_model(g_model);
        g_model = nullptr;
    }
    
    model_loaded = false;
    LOGI("Model unloaded");
}
