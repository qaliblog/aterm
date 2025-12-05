# llama.cpp Integration Status

## Current Status

The local LLM integration framework is **complete** and ready for testing, but the actual `llama.cpp` native library integration is still a **placeholder**.

## What's Working

✅ **UI Integration**
- Local Model Settings screen with file picker
- Local Model Chat screen for direct interaction
- Agent integration (uses local model when `BUILTIN_LOCAL` provider is selected)
- Model file selection via Android SAF (Storage Access Framework)
- URI to file path conversion (handles content:// URIs)
- Model loading state management

✅ **Kotlin Layer**
- `LocalLlamaModel` object with `loadModel()`, `generate()`, `isLoaded()` methods
- Automatic model loading from saved preferences
- Error handling and logging

✅ **Native Bridge**
- JNI functions declared: `loadModelNative()`, `generateNative()`, `unloadModelNative()`
- CMake build configuration for arm64-v8a
- Native library loading (`System.loadLibrary("llama")`)

## What Needs Implementation

❌ **Actual llama.cpp Integration**

The current `llama_jni.cpp` only contains placeholder implementations that:
- Check if the model file exists
- Return a placeholder response: "This is a placeholder response. llama.cpp integration pending."

### Required Steps

1. **Add llama.cpp Source Code**
   - Clone or add llama.cpp repository to `app/src/main/cpp/llama.cpp/`
   - Or use a pre-built library approach

2. **Update CMakeLists.txt**
   ```cmake
   # Add llama.cpp source files
   add_subdirectory(llama.cpp)
   # Link against llama library
   target_link_libraries(llama llama)
   ```

3. **Implement JNI Functions**
   - `loadModelNative()`: Call `llama_load_model_from_file()` or equivalent
   - `generateNative()`: Call `llama_generate()` or equivalent with proper context
   - `unloadModelNative()`: Clean up model context and free memory

4. **Model Context Management**
   - Store llama context pointer globally or in a struct
   - Handle model parameters (n_ctx, n_threads, etc.)
   - Implement proper memory management

## Testing

Even with placeholder code, you can test:
1. **File Selection**: Select a GGUF model file in Settings → API Provider → Built-in Local Model
2. **Model Loading**: The file path will be resolved and saved
3. **UI Flow**: Chat screen and agent will attempt to use the local model
4. **Error Handling**: Proper error messages if model isn't loaded

## Example llama.cpp Integration (Reference)

```cpp
#include "llama.h"

static llama_context* g_ctx = nullptr;
static llama_model* g_model = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_qali_aterm_llm_LocalLlamaModel_loadModelNative(JNIEnv *env, jobject thiz, jstring path) {
    const char *path_str = env->GetStringUTFChars(path, nullptr);
    
    // Load model
    llama_model_params model_params = llama_model_default_params();
    g_model = llama_load_model_from_file(path_str, model_params);
    
    if (g_model == nullptr) {
        LOGE("Failed to load model");
        env->ReleaseStringUTFChars(path, path_str);
        return JNI_FALSE;
    }
    
    // Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;  // Context size
    ctx_params.n_threads = 4;  // Thread count
    
    g_ctx = llama_new_context_with_model(g_model, ctx_params);
    
    env->ReleaseStringUTFChars(path, path_str);
    return g_ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qali_aterm_llm_LocalLlamaModel_generateNative(JNIEnv *env, jobject thiz, jstring prompt) {
    if (g_ctx == nullptr) {
        return env->NewStringUTF("Error: Model not loaded");
    }
    
    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    
    // Tokenize prompt
    std::vector<llama_token> tokens = llama_tokenize(g_ctx, prompt_str, true);
    
    // Generate
    llama_decode(g_ctx, llama_batch_get_one(&tokens[0], tokens.size(), 0, 0));
    
    // ... (complete generation loop)
    
    env->ReleaseStringUTFChars(prompt, prompt_str);
    return env->NewStringUTF(response.c_str());
}
```

## Next Steps

1. Decide on llama.cpp integration approach:
   - **Option A**: Add llama.cpp as submodule and build from source
   - **Option B**: Use pre-built llama.cpp library
   - **Option C**: Use a wrapper library like `llama-cpp-python` bindings

2. Update `llama_jni.cpp` with actual implementation

3. Test with a small model first (e.g., Qwen2.5-0.5B) before larger models

4. Optimize for Android:
   - Use appropriate thread count
   - Manage memory efficiently
   - Consider quantization (Q4_K_M, Q5_K_M, etc.)
