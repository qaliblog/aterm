## AutoAgent Upgrade 01 — Tasks & Status

### Phase 1: Core AutoAgent / DB Model Name
- [x] Define separate **Database Model Name** preference independent from text classifier
- [x] Default DB name should be **`aterm-db`**, not tied to any classifier label
- [x] Ensure **DB is created and initialized with framework knowledge** whenever DB name is set/changed
- [x] Expose **DB name in settings** and allow editing via dialog
- [x] Reset DB should re-create DB and re-populate framework knowledge for current DB name

### Phase 2: AutoAgent Settings & Model Management
- [x] Create dedicated **AutoAgent Settings** section, separate from other providers
- [x] Move classification model selection UI into **AutoAgent Settings**
- [x] Keep API provider model selection (Gemini/Others) separate in **API Provider Settings**
- [x] Add **Reset AutoAgent DB** action under AutoAgent settings with confirmation
- [x] Add downloadable **CodeBERT TFLite** built-in model entry (with .tflite handling)

### Phase 3: Learning & Background Behavior
- [x] Confirm AutoAgent continues **background learning** when other providers are active
- [x] Ensure learning logs and statistics are surfaced in **Debug Info / AutoAgent Debug Information**
- [x] Verify **framework knowledge base** is always loaded into the selected DB (including new DB names)

### Phase 4: Gemini – Prompt Classification (Question vs Debug)
- [x] Improve **question vs debug detection** for Gemini:
  - [x] Add a lightweight classifier that:
    - [x] Detects **DEBUG/ERROR** prompts (stack traces, "Exception", "Traceback", "error: ", test failures, linter output)
    - [x] Detects **QUESTION/EXPLANATION** prompts ("what", "why", "how", "explain", QA style)
    - [x] Detects **RUN_TEST** intents ("run tests", "run pytest", "gradlew test", etc.)
  - [x] Map these to existing `IntentType` values (e.g. `QUESTION_ONLY`, `DEBUG_UPGRADE`, `TEST_ONLY`, `GENERAL`)
  - [x] Log **detected prompt type** in `GeminiClient` for observability

### Phase 5: Gemini – Coherent Code Generation from Metadata
- [x] Improve **code coherence** when Gemini generates or fixes code:
  - [x] When intent is code-related (create/fix), build a **structured context**:
    - [x] Primary file(s) first, then dependency files, then supporting context
    - [x] Inline **file names**, **function names**, and **framework type** explicitly in the system/assistant prompt
    - [x] Clearly specify what files may be created/modified
  - [x] Ensure code completion **only uses given metadata** and file roles (no new files/functions unless requested)
  - [x] Strengthen prompt instructions for code tasks without adding any runtime import checking

### Phase 6: Verification & UX
- [x] Add small debug logging around Gemini prompt classification and context building
- [x] Manually test:
  - [x] Simple question prompts (should not trigger debug flow)
  - [x] Real error/stacktrace prompts (should trigger debug flow / reverse flow)
  - [x] Code generation prompts (like tic tac toe Node.js webapp) to verify more coherent, metadata-aware output

### Phase 7: AutoAgent – Stronger Code Generation & Replication of Gemini Work
- [x] Improve **AutoAgent code generation quality** so it can replicate/approximate Gemini solutions:
  - [x] Use `PromptAnalyzer` output (intent, frameworkType, metadata) more aggressively when building AutoAgent context
  - [x] Prefer **framework knowledge + learned patterns** for the detected framework (e.g., Node.js, JavaScript) before falling back to generic snippets
  - [x] Ensure generated code respects **file roles** and **function names** from metadata (e.g., main entry file, handler functions)
- [x] Enhance **learning from Gemini code** when AutoAgent is inactive:
  - [x] Tag learned entries explicitly with `source = "gemini"` and store prompt pattern + frameworkType
  - [x] Prioritize `source = "gemini"` entries when searching for patterns to answer similar prompts later
- [x] Add **specialized templates** for common tasks (e.g., tic-tac-toe webapp, CRUD API, auth flow) into the framework knowledge base:
  - [x] HTML/CSS/JS single-page app skeletons
  - [x] Node.js + Express basic game/CRUD server templates
- [x] Implement a **self-check pass** inside AutoAgentProvider:
  - [x] After generating code, quickly scan for required files/functions from metadata and log if something is missing (for debugging, not blocking)
  - [x] Log a short summary when AutoAgent fails to match a known pattern so we can refine templates/learning later
