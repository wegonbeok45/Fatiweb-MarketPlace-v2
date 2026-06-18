import {logger} from "firebase-functions/v2";

export type AiRole = "system" | "user" | "assistant";

export type AiTextPart = {
  type: "text";
  text: string;
};

export type AiImagePart = {
  type: "image_url";
  image_url: {
    url: string;
    detail?: string;
  };
};

export type AiContentPart = AiTextPart | AiImagePart;

export type AiMessage = {
  role: AiRole;
  content: string | AiContentPart[];
};

export type AiResponseFormat = {
  type: "json_object" | "text";
};

export type AiChatRequest = {
  messages: AiMessage[];
  model?: string;
  responseFormat?: AiResponseFormat;
  temperature?: number;
  topP?: number;
  maxTokens?: number;
  logTag?: string;
};

let _aiClient: any = null;

async function getAiClient() {
  if (!_aiClient) {
    // Dynamic import to support ESM in a CommonJS project
    const {GoogleGenAI} = await import("@google/genai");
    _aiClient = new GoogleGenAI({
      vertexai: true,
      project: process.env.GCLOUD_PROJECT || "fatiweb-marketplace",
      location: "europe-west1",
    });
  }
  return _aiClient;
}

/**
 * Send a Vertex AI chat completion request and return the assistant text.
 */
export async function chatCompletion(req: AiChatRequest): Promise<string> {
  const ai = await getAiClient();
  const tag = req.logTag || "firebase-ai";
  const modelName = req.model || "gemini-2.5-flash";

  logger.debug(`[${tag}] Sending request to Firebase Vertex AI`, {model: modelName});

  const systemInstructions: string[] = [];
  const contents: any[] = [];

  for (const msg of req.messages) {
    if (msg.role === "system") {
      if (typeof msg.content === "string") {
        systemInstructions.push(msg.content);
      } else {
        for (const part of msg.content) {
          if (part.type === "text") {
            systemInstructions.push(part.text);
          }
        }
      }
    } else {
      const role = msg.role === "assistant" ? "model" : "user";
      const parts: any[] = [];

      if (typeof msg.content === "string") {
        parts.push({text: msg.content});
      } else {
        for (const part of msg.content) {
          if (part.type === "text") {
            parts.push({text: part.text});
          } else if (part.type === "image_url") {
            const url = part.image_url.url;
            if (url.startsWith("data:")) {
              const match = url.match(/^data:(image\/[a-zA-Z0-9.+]+);base64,(.*)$/);
              if (match) {
                parts.push({
                  inlineData: {
                    mimeType: match[1],
                    data: match[2],
                  },
                });
              } else {
                logger.warn(`[${tag}] Could not parse data URI mime type.`);
              }
            } else {
              logger.warn(`[${tag}] Unsupported image URL format. Only data: URIs are supported.`, {url: url.substring(0, 30)});
            }
          }
        }
      }

      contents.push({role, parts});
    }
  }

  const config: any = {};
  if (req.responseFormat?.type === "json_object") {
    config.responseMimeType = "application/json";
  }
  if (req.temperature !== undefined) config.temperature = req.temperature;
  if (req.topP !== undefined) config.topP = req.topP;
  if (req.maxTokens !== undefined) config.maxOutputTokens = req.maxTokens;
  if (systemInstructions.length > 0) {
    config.systemInstruction = {
      parts: systemInstructions.map((t) => ({text: t})),
    };
  }

  try {
    const response = await ai.models.generateContent({
      model: modelName,
      contents,
      config,
    });

    const text = response.text;

    if (!text) {
      logger.warn(`[${tag}] Vertex AI returned empty content`);
      return "";
    }

    return text;
  } catch (e: any) {
    logger.error(`[${tag}] Vertex AI request failed`, {error: e.message});
    throw e;
  }
}
