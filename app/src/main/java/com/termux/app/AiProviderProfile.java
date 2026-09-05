package com.termux.app;

import androidx.annotation.Nullable;

/** khabeer-style provider profile shown by the mobile app. */
public final class AiProviderProfile {

    public final String id;
    public final String name;
    public final String mark;
    public final String description;
    public final String defaultBaseUrl;
    public final String defaultModel;
    public final boolean apiKeyAuth;
    public final boolean oauthAuth;
    public final boolean terminalOnly;
    public final boolean implemented;
    /** Request dialect: responses | chat | anthropic | codex. Builtins carry
     * their dialect; runtime falls back to the legacy id tables when null. */
    public final String dialect;
    /** True for runtime-loaded profiles (custom endpoints, plugin
     * providers) — editable and deletable from the UI. */
    public final boolean custom;

    private AiProviderProfile(String id, String name, String mark, String description,
                              String defaultBaseUrl, String defaultModel,
                              boolean apiKeyAuth, boolean oauthAuth, boolean terminalOnly,
                              boolean implemented, String dialect, boolean custom) {
        this.id = id;
        this.name = name;
        this.mark = mark;
        this.description = description;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.apiKeyAuth = apiKeyAuth;
        this.oauthAuth = oauthAuth;
        this.terminalOnly = terminalOnly;
        this.implemented = implemented;
        this.dialect = dialect;
        this.custom = custom;
    }

    private AiProviderProfile(String id, String name, String mark, String description,
                              String defaultBaseUrl, String defaultModel,
                              boolean apiKeyAuth, boolean oauthAuth, boolean terminalOnly,
                              boolean implemented) {
        this(id, name, mark, description, defaultBaseUrl, defaultModel,
            apiKeyAuth, oauthAuth, terminalOnly, implemented, null, false);
    }

    /** Runtime-loaded provider profile (custom endpoint or plugin). Only
     * key-or-keyless chat/responses/anthropic dialects are expressible —
     * OAuth flows stay builtin-only. */
    public static AiProviderProfile customProfile(String id, String name, String mark,
                                                  String description, String baseUrl,
                                                  String model, boolean needsKey,
                                                  String dialect) {
        String safeDialect = "anthropic".equals(dialect) ? "anthropic"
            : "responses".equals(dialect) ? "responses" : "chat";
        return new AiProviderProfile(id, name, mark, description,
            baseUrl == null ? "" : baseUrl, model == null ? "" : model,
            needsKey, false, false, true, safeDialect, true);
    }

    public static final AiProviderProfile[] PROFILES = new AiProviderProfile[]{
        new AiProviderProfile("openai", "OpenAI", "O",
            "Native OpenAI Responses API agent with Termux terminal tools.",
            "https://api.openai.com/v1/responses", "gpt-5.6", true, false, false, true),
        new AiProviderProfile("anthropic", "Anthropic", "A",
            "Claude models via API key, or Claude Pro/Max via subscription sign-in.",
            "https://api.anthropic.com/v1/messages", "claude-sonnet-4-5", true, true, false, true),
        new AiProviderProfile("opencode", "OpenCode", "OC",
            "OpenCode Free, Zen, and Go routes. Free is keyless; Zen/Go can use API keys.",
            "https://opencode.ai/zen/v1", "big-pickle", false, false, false, true),
        new AiProviderProfile("openrouter", "OpenRouter", "OR",
            "OpenAI-compatible model routing for many hosted models.",
            "https://openrouter.ai/api/v1/responses", "openai/gpt-5.6", true, false, false, true),
        new AiProviderProfile("nous", "Nous Portal", "N",
            "Nous Portal subscription — device-code sign-in, 300+ models.",
            "https://inference-api.nousresearch.com/v1/chat/completions", "Hermes-4.5-405B", false, true, false, false),
        new AiProviderProfile("openai-codex", "OpenAI Codex", "CX",
            "ChatGPT Plus/Pro subscription login — device-code sign-in, Codex models.",
            "https://chatgpt.com/backend-api/codex", "gpt-5.3-codex", false, true, false, true),
        new AiProviderProfile("github-copilot", "GitHub Copilot", "GH",
            "Copilot subscription — GitHub device-code sign-in.",
            "https://api.githubcopilot.com", "gpt-5", false, true, false, false),
        new AiProviderProfile("fireworks", "Fireworks AI", "FW",
            "Fireworks AI hosted models via FIREWORKS_API_KEY.",
            "https://api.fireworks.ai/inference/v1/chat/completions", "accounts/fireworks/models/kimi-k2-instruct", true, false, false, true),
        new AiProviderProfile("novita", "NovitaAI", "NV",
            "NovitaAI model API via NOVITA_API_KEY.",
            "https://api.novita.ai/v3/openai/chat/completions", "qwen/qwen3-coder", true, false, false, true),
        new AiProviderProfile("ai-gateway", "AI Gateway", "AG",
            "Vercel AI Gateway — one key, many providers.",
            "https://ai-gateway.vercel.sh/v1/chat/completions", "openai/gpt-5.6", true, false, false, true),
        new AiProviderProfile("zai", "z.ai / GLM", "GLM",
            "Z.AI GLM coding models via GLM_API_KEY.",
            "https://api.z.ai/api/paas/v4/chat/completions", "glm-4.6", true, false, false, true),
        new AiProviderProfile("kimi-coding", "Kimi / Moonshot", "K",
            "Kimi coding plan via the Anthropic-compatible /coding endpoint.",
            "https://api.kimi.com/coding/v1/messages", "kimi-k2-thinking", true, false, false, true),
        new AiProviderProfile("arcee", "Arcee AI", "AR",
            "Arcee AI hosted models.",
            "https://api.arcee.ai/api/v1/chat/completions", "arcee-afm", true, false, false, true),
        new AiProviderProfile("gmi", "GMI Cloud", "GMI",
            "GMI Cloud hosted models.",
            "https://api.gmi-serving.com/v1/chat/completions", "deepseek-ai/DeepSeek-V3.1", true, false, false, true),
        new AiProviderProfile("actual", "Actual Computer", "AC",
            "Actual Computer hosted relay (Responses API).",
            "https://api.actual.inc/v1/responses", "auto", true, false, false, false),
        new AiProviderProfile("minimax", "MiniMax", "MM",
            "MiniMax coding plan via the Anthropic-compatible endpoint.",
            "https://api.minimax.io/anthropic/v1/messages", "MiniMax-M2.7", true, false, false, true),
        new AiProviderProfile("xai", "xAI / Grok", "X",
            "Grok models via the Responses API (API key or SuperGrok login).",
            "https://api.x.ai/v1/responses", "grok-code-fast-1", true, true, false, true),
        new AiProviderProfile("alibaba", "Qwen / Alibaba", "Q",
            "Alibaba DashScope international (OpenAI-compatible).",
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen3-coder-plus", true, false, false, true),
        new AiProviderProfile("kilocode", "Kilo Code", "KC",
            "Kilo Code gateway.",
            "https://api.kilo.ai/api/gateway/chat/completions", "auto", true, false, false, true),
        new AiProviderProfile("xiaomi", "Xiaomi MiMo", "MI",
            "Xiaomi MiMo hosted models.",
            "https://api.xiaomimimo.com/v1/chat/completions", "MiMo", true, false, false, true),
        new AiProviderProfile("tencent-tokenhub", "Tencent TokenHub", "TT",
            "Tencent TokenHub (OpenAI-compatible).",
            "https://tokenhub.tencentmaas.com/v1/chat/completions", "auto", true, false, false, true),
        new AiProviderProfile("deepseek", "DeepSeek", "DS",
            "DeepSeek V3.x chat models.",
            "https://api.deepseek.com/v1/chat/completions", "deepseek-chat", true, false, false, true),
        new AiProviderProfile("huggingface", "Hugging Face", "HF",
            "Hugging Face Inference Providers router.",
            "https://router.huggingface.co/v1/chat/completions", "auto", true, false, false, true),
        new AiProviderProfile("gemini", "Google Gemini", "G",
            "Gemini via the OpenAI-compatible endpoint and GEMINI_API_KEY.",
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-2.5-pro", true, false, false, true),
        new AiProviderProfile("vertex", "Google Vertex AI", "VX",
            "Vertex AI using GCP credentials — needs a service account.",
            "", "gemini-2.5-pro", false, true, false, false),
        new AiProviderProfile("azure-foundry", "Azure AI Foundry", "AZ",
            "Azure OpenAI / Foundry endpoints — needs your resource URL.",
            "", "auto", true, false, false, false),
        new AiProviderProfile("bedrock", "AWS Bedrock", "AWS",
            "AWS Bedrock via the Converse API — needs AWS credentials.",
            "", "auto", false, false, false, false),
        new AiProviderProfile("nvidia", "NVIDIA Build", "NV",
            "NVIDIA NIM hosted models.",
            "https://integrate.api.nvidia.com/v1/chat/completions", "auto", true, false, false, true),
        new AiProviderProfile("ollama-cloud", "Ollama Cloud", "OL",
            "Ollama's hosted models.",
            "https://ollama.com/v1/chat/completions", "gpt-oss:120b", true, false, false, true),
        new AiProviderProfile("qwen-oauth", "Qwen OAuth", "QO",
            "Qwen free-tier — sign in with the Qwen CLI on a computer.",
            "https://portal.qwen.ai/v1/chat/completions", "qwen3-coder-plus", false, true, false, false),
        new AiProviderProfile("lmstudio", "LM Studio", "LM",
            "Local LM Studio server on this device's network.",
            "http://127.0.0.1:1234/v1/chat/completions", "local-model", false, false, false, false),
        new AiProviderProfile("custom", "Custom endpoint", "•",
            "Any OpenAI-compatible Responses endpoint.",
            "", "gpt-5.6", true, false, false, true)
    };

    public static final String[] FEATURED_PROVIDER_IDS = new String[]{"openai", "anthropic", "opencode"};

    private static volatile java.util.List<AiProviderProfile> sOverlay =
        java.util.Collections.emptyList();

    /** Runtime-loaded profiles (custom endpoints + plugin providers),
     * refreshed by AiPluginRegistry on start and on every change. */
    public static void setOverlay(@Nullable java.util.List<AiProviderProfile> overlay) {
        sOverlay = overlay == null
            ? java.util.Collections.emptyList()
            : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(overlay));
    }

    /** Builtins first, then runtime-loaded profiles. */
    public static java.util.List<AiProviderProfile> all() {
        java.util.List<AiProviderProfile> out =
            new java.util.ArrayList<>(java.util.Arrays.asList(PROFILES));
        out.addAll(sOverlay);
        return out;
    }

    @Nullable
    public static AiProviderProfile find(String id) {
        if (id == null) return null;
        for (AiProviderProfile profile : PROFILES) {
            if (id.equals(profile.id)) return profile;
        }
        for (AiProviderProfile profile : sOverlay) {
            if (id.equals(profile.id)) return profile;
        }
        return null;
    }

    public static AiProviderProfile firstAgentProfile() {
        return PROFILES[0];
    }
}
