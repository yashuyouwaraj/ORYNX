/**
 * Lightweight, privacy-preserving frontend observability utilities.
 * - Generates W3C Trace Context (traceparent) and correlation IDs for outgoing requests.
 * - Never captures authentication tokens, passwords, or form payloads.
 * - Degrades gracefully when observability infrastructure is absent.
 */

export interface FrontendTraceContext {
  traceId: string;
  spanId: string;
  traceparent: string;
  correlationId: string;
}

export interface SafeApiErrorContext {
  endpoint: string;
  status: number;
  correlationId: string;
  traceId: string;
}

const randomHex = (byteLength: number): string => {
  const bytes = new Uint8Array(byteLength);
  if (typeof globalThis !== "undefined" && globalThis.crypto?.getRandomValues) {
    globalThis.crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < byteLength; i++) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }
  // Ensure non-zero per W3C Trace Context spec
  if (bytes.every((b) => b === 0)) {
    bytes[byteLength - 1] = 1;
  }
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
};

export const generateTraceContext = (): FrontendTraceContext => {
  const traceId = randomHex(16);
  const spanId = randomHex(8);
  const correlationId =
    typeof globalThis !== "undefined" && globalThis.crypto?.randomUUID
      ? globalThis.crypto.randomUUID()
      : `${traceId.slice(0, 8)}-${traceId.slice(8, 12)}-4${traceId.slice(13, 16)}-a${traceId.slice(17, 20)}-${traceId.slice(20, 32)}`;

  return {
    traceId,
    spanId,
    traceparent: `00-${traceId}-${spanId}-01`,
    correlationId,
  };
};

export const sanitizeEndpointForLogging = (url: string): string => {
  const idx = url.indexOf("?");
  return idx >= 0 ? url.slice(0, idx) : url;
};

export const createSafeApiErrorContext = (
  url: string,
  status: number,
  traceCtx: FrontendTraceContext,
): SafeApiErrorContext => ({
  endpoint: sanitizeEndpointForLogging(url),
  status,
  correlationId: traceCtx.correlationId,
  traceId: traceCtx.traceId,
});

export const buildGrafanaTraceUrl = (traceId: string): string | null => {
  const base = process.env.NEXT_PUBLIC_GRAFANA_URL;
  if (!base || !/^[0-9a-fA-F]{32}$/.test(traceId)) {
    return null;
  }
  const cleanBase = base.replace(/\/+$/, "");
  const query = encodeURIComponent(
    JSON.stringify({ datasource: "tempo", queries: [{ query: traceId, queryType: "traceql" }] }),
  );
  return `${cleanBase}/explore?left=${query}`;
};

export const buildGrafanaWorkflowLogsUrl = (workflowId: number): string | null => {
  const base = process.env.NEXT_PUBLIC_GRAFANA_URL;
  if (!base || !Number.isInteger(workflowId) || workflowId <= 0) {
    return null;
  }
  const cleanBase = base.replace(/\/+$/, "");
  const query = encodeURIComponent(
    JSON.stringify({
      datasource: "loki",
      queries: [{ expr: `{service_name=~".*-service"} | workflowId="${workflowId}"` }],
    }),
  );
  return `${cleanBase}/explore?left=${query}`;
};
