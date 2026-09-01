package com.termux.app;

import androidx.annotation.Nullable;

/** katheer-style provider profile shown by the mobile app. */
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

    private AiProviderProfile(String id, String name, String mark, String description,
                              String defaultBaseUrl, String defaultModel,
                              boolean apiKeyAuth, boolean oauthAuth, boolean terminalOnly,
                              boolean implemented) {
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
    }

    public static final AiProviderProfile[] PROFILES = new AiProviderProfile[]{
        new AiProviderProfile("openai", "OpenAI", "O",
            "Native OpenAI Responses API agent with Termux terminal tools.",
            "https://api.openai.com/v1/responses", "gpt-5.6", true, false, false, true),
        new AiProviderProfile("anthropic", "Anthropic", "A",
            "Claude provider registered by katheer. Native Anthropic adapter is next.",
            "https://api.anthropic.com/v1/messages", "claude-sonnet-4-5", true, true, false, false),
        new AiProviderProfile("opencode", "OpenCode", "OC",
            "OpenCode Free, Zen, and Go routes. Free is keyless; Zen/Go can use API keys.",
            "https://opencode.ai/zen/v1", "big-pickle", false, false, false, true),
        new AiProviderProfile("openrouter", "OpenRouter", "OR",
            "OpenAI-compatible model routing for many hosted models.",
            "https://openrouter.ai/api/v1/responses", "openai/gpt-5.6", true, false, false, true),
        new AiProviderProfile("nous", "Nous Portal", "N",
            "katheer-style provider profile. OAuth can be wired to Nous Portal next.",
            "https://portal.nousresearch.com/v1/responses", "qwen/qwen3-coder", true, true, false, false),
        new AiProviderProfile("openai-codex", "OpenAI Codex", "CX",
            "katheer ChatGPT/Codex subscription provider using device-code OAuth.",
            "", "gpt-5.1-codex", false, true, false, false),
        new AiProviderProfile("github-copilot", "GitHub Copilot", "GH",
            "katheer Copilot provider using OAuth/device-code or GitHub tokens.",
            "", "copilot", true, true, false, false),
        new AiProviderProfile("fireworks", "Fireworks AI", "FW",
            "katheer Fireworks provider via FIREWORKS_API_KEY.",
            "https://api.fireworks.ai/inference/v1/responses", "accounts/fireworks/models/qwen3-coder-480b-a35b-instruct", true, false, false, false),
        new AiProviderProfile("novita", "NovitaAI", "NV",
            "katheer NovitaAI provider for model API, sandbox, and GPU cloud routes.",
            "", "qwen/qwen3-coder", true, false, false, false),
        new AiProviderProfile("ai-gateway", "AI Gateway", "AG",
            "katheer AI Gateway provider.",
            "", "auto", true, false, false, false),
        new AiProviderProfile("zai", "z.ai / GLM", "GLM",
            "katheer z.ai / GLM provider via GLM_API_KEY.",
            "", "glm-4.6", true, false, false, false),
        new AiProviderProfile("kimi-coding", "Kimi / Moonshot", "K",
            "katheer Kimi coding provider.",
            "", "kimi-k2", true, false, false, false),
        new AiProviderProfile("arcee", "Arcee AI", "AR",
            "katheer Arcee AI provider.",
            "", "auto", true, false, false, false),
        new AiProviderProfile("gmi", "GMI Cloud", "GMI",
            "katheer GMI Cloud provider.",
            "", "auto", true, false, false, false),
        new AiProviderProfile("actual", "Actual Computer", "AC",
            "katheer Actual Computer hosted relay or local daemon provider.",
            "", "auto", true, false, false, false),
        new AiProviderProfile("minimax", "MiniMax", "MM",
            "katheer MiniMax API-key provider.",
            "", "auto", true, false, false, false),
        new AiProviderProfile("xai", "xAI / Grok", "X",
            "katheer xAI Responses API and SuperGrok OAuth family.",
            "", "grok-code-fast-1", true, true, false, false),
        new AiProviderProfile("alibaba", "Qwen / Alibaba", "Q",
            "katheer Alibaba DashScope / Qwen cloud provider.",
            "", "qwen3-coder-plus", true, false, false, false),
        new AiProviderProfile("kilocode", "Kilo Code", "KC",
            "katheer Kilo Code provider.",
            "", "auto", true, false, false, false),
        new AiProviderProfile("xiaomi", "Xiaomi MiMo", "MI",
            "katheer Xiaomi MiMo provider.",
            "", "auto", true, false, false, false),
        new AiProviderProfile("tencent-tokenhub", "Tencent TokenHub", "TT",
            "katheer Tencent TokenHub provider.",
            "", "auto", true, false, false, false),
        new AiProviderProfile("deepseek", "DeepSeek", "DS",
            "katheer DeepSeek provider.",
            "", "deepseek-chat", true, false, false, false),
        new AiProviderProfile("huggingface", "Hugging Face", "HF",
            "katheer Hugging Face provider.",
            "", "auto", true, false, false, false),
        new AiProviderProfile("gemini", "Google Gemini", "G",
            "katheer Gemini API-key provider.",
            "", "gemini-2.5-pro", true, false, false, false),
        new AiProviderProfile("vertex", "Google Vertex AI", "VX",
            "katheer Vertex AI provider using GCP credentials.",
            "", "gemini-2.5-pro", false, true, false, false),
        new AiProviderProfile("azure-foundry", "Azure AI Foundry", "AZ",
            "katheer Azure OpenAI / Foundry endpoint provider.",
            "", "auto", true, false, false, false),
        new AiProviderProfile("bedrock", "AWS Bedrock", "AWS",
            "katheer AWS Bedrock provider using the AWS credential chain.",
            "", "auto", false, false, false, false),
        new AiProviderProfile("nvidia", "NVIDIA Build", "NV",
            "katheer NVIDIA NIM-hosted model provider.",
            "", "auto", true, false, false, false),
        new AiProviderProfile("ollama-cloud", "Ollama Cloud", "OL",
            "katheer cloud-hosted Ollama API provider.",
            "", "auto", true, false, false, false),
        new AiProviderProfile("qwen-oauth", "Qwen OAuth", "QO",
            "katheer Qwen OAuth browser PKCE provider.",
            "", "qwen3-coder-plus", false, true, false, false),
        new AiProviderProfile("lmstudio", "LM Studio", "LM",
            "katheer LM Studio provider for local OpenAI-compatible servers.",
            "http://127.0.0.1:1234/v1/responses", "local-model", false, false, false, false),
        new AiProviderProfile("custom", "Custom endpoint", "•",
            "Any OpenAI-compatible Responses endpoint.",
            "", "gpt-5.6", true, false, false, true)
    };

    public static final String[] FEATURED_PROVIDER_IDS = new String[]{"openai", "anthropic", "opencode"};

    @Nullable
    public static AiProviderProfile find(String id) {
        if (id == null) return null;
        for (AiProviderProfile profile : PROFILES) {
            if (id.equals(profile.id)) return profile;
        }
        return null;
    }

    public static AiProviderProfile firstAgentProfile() {
        return PROFILES[0];
    }
}
