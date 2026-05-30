import {logger} from "firebase-functions";
import {HttpsError} from "firebase-functions/v2/https";

/**
 * Shared Groq client.
 *
 * Single source of truth for endpoint, auth, and error mapping.
 * Uses the OpenAI-compatible Chat Completions endpoint
 * (https://api.groq.com/openai/v1/chat/completions) so both text and vision
 * requests share the same shape.
 *
 * The API key is loaded lazily by each callable function via its
 * `secrets: [groqApiKey]` declaration. This module reads it at call time
 * from the `apiKey` argument — never from process.env directly — to keep
 * the secret out of cold-start globals.
 */

export type GroqRole = "system" | "user" | "assistant";

export type GroqTextPart = {
  type: "text";
  text: string;
};

export type GroqImagePart = {
  type: "image_url";
  image_url: {
    url: string;
    detail?: "low" | "high" | "auto";
  };
};

export type GroqContentPart = GroqTextPart | GroqImagePart;

export type GroqMessage = {
  role: GroqRole;
  /** Either a plain string (text-only turn) or an array of parts (multimodal). */
  content: string | GroqContentPart[];
};

export type GroqResponseFormat =
  | {type: "text"}
  | {type: "json_object"};

export type GroqChatRequest = {
  apiKey: string;
  model: string;
  messages: GroqMessage[];
  temperature?: number;
  maxTokens?: number;
  topP?: number;
  responseFormat?: GroqResponseFormat;
  /** Ordered list of fallback models to try on 503 / empty reply. */
  fallbackModels?: string[];
  /** Tag used in log lines so callers are distinguishable. */
  logTag?: string;
};

type GroqChoice = {
  message?: {content?: string};
  finish_reason?: string;
};

type GroqRawResponse = {
  choices?: GroqChoice[];
  error?: {message?: string; code?: string};
};

const ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";

/**
 * Send a Groq chat completion request and return the assistant text.
 *
 * Throws an `HttpsError` with a stable code/message on every error path so
 * callers can re-throw without translation.
 */
export async function chatCompletion(req: GroqChatRequest): Promise<string> {
  const modelsToTry = [req.model, ...(req.fallbackModels || [])];
  const tag = req.logTag || "groq";

  let lastTransientError: {status: number; body: string} | null = null;

  for (const model of modelsToTry) {
    const body = JSON.stringify(buildRequestBody(req, model));

    let response: Response;
    try {
      response = await fetch(ENDPOINT, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${req.apiKey}`,
        },
        body,
      });
    } catch (networkError) {
      logger.error(`[${tag}] Network error reaching Groq`, {
        model,
        error: (networkError as Error).message,
      });
      throw new HttpsError("unavailable", "Assistant is temporarily unavailable.");
    }

    const responseText = await response.text();

    if (!response.ok) {
      const snippet = responseText.slice(0, 400);
      logger.error(`[${tag}] Groq request failed`, {
        model,
        status: response.status,
        body: snippet,
      });

      if (response.status === 401 || response.status === 403) {
        throw new HttpsError("failed-precondition", "Groq API key is invalid or unauthorized.");
      }
      if (response.status === 429) {
        throw new HttpsError("resource-exhausted", "Groq rate limit reached. Please retry shortly.");
      }
      // 5xx → try next fallback model
      if (response.status >= 500) {
        lastTransientError = {status: response.status, body: snippet};
        continue;
      }
      throw new HttpsError("internal", "Assistant request was rejected.");
    }

    let parsed: GroqRawResponse;
    try {
      parsed = JSON.parse(responseText) as GroqRawResponse;
    } catch {
      logger.error(`[${tag}] Failed to parse Groq response`, {
        model,
        bodySnippet: responseText.slice(0, 400),
      });
      lastTransientError = {status: 0, body: responseText.slice(0, 400)};
      continue;
    }

    const reply = parsed.choices?.[0]?.message?.content?.trim() || "";
    if (reply) {
      return reply;
    }

    logger.warn(`[${tag}] Groq returned empty content`, {model});
    lastTransientError = {status: 0, body: "empty"};
  }

  if (lastTransientError) {
    logger.error(`[${tag}] All Groq models exhausted`, lastTransientError);
  }
  throw new HttpsError("internal", "Assistant returned an empty response.");
}

function buildRequestBody(req: GroqChatRequest, model: string): Record<string, unknown> {
  const body: Record<string, unknown> = {
    model,
    messages: req.messages,
  };
  if (typeof req.temperature === "number") body.temperature = req.temperature;
  if (typeof req.maxTokens === "number") body.max_tokens = req.maxTokens;
  if (typeof req.topP === "number") body.top_p = req.topP;
  if (req.responseFormat) body.response_format = req.responseFormat;
  return body;
}
