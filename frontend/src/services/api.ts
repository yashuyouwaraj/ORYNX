import type { Workflow } from "@/types/workflow";

type PageEnvelope<T> = {
  content: T[];
  [key: string]: unknown;
};

const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

const createHeaders = (token?: string): HeadersInit => {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }
  return headers;
};

export const getWorkflows = async (
  token?: string,
  page = 0,
  size = 50
): Promise<Workflow[]> => {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/workflows?page=${page}&size=${size}`,
    {
      headers: createHeaders(token),
    }
  );

  if (!response.ok) {
    throw new Error("Failed to fetch workflows");
  }

  const data = (await response.json()) as Workflow[] | PageEnvelope<Workflow>;

  if (Array.isArray(data)) {
    return data;
  }

  if (data && Array.isArray((data as PageEnvelope<Workflow>).content)) {
    return (data as PageEnvelope<Workflow>).content;
  }

  return [];
};

export const getAnalytics = async (token?: string) => {
  const response = await fetch(`${API_BASE_URL}/api/v1/analytics`, {
    headers: createHeaders(token),
  });

  if (!response.ok) {
    throw new Error("Failed to fetch analytics");
  }

  return response.json();
};

export const getTimeline = async (
  workflowId: number,
  token?: string,
  page = 0,
  size = 50
) => {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/timeline/${workflowId}?page=${page}&size=${size}`,
    {
      headers: createHeaders(token),
    }
  );

  if (!response.ok) {
    throw new Error("Failed to fetch timeline");
  }

  const data = await response.json();
  if (Array.isArray(data)) {
    return data;
  }
  if (data && Array.isArray(data.content)) {
    return data.content;
  }
  return [];
};

export const getPerformance = async (token?: string) => {
  const response = await fetch(`${API_BASE_URL}/api/v1/performance`, {
    headers: createHeaders(token),
  });

  if (!response.ok) {
    throw new Error("Failed to fetch performance");
  }

  return response.json();
};

export const getPerformanceSummary = async (token?: string) => {
  const response = await fetch(`${API_BASE_URL}/api/v1/performance/summary`, {
    headers: createHeaders(token),
  });

  if (!response.ok) {
    throw new Error("Failed to fetch performance summary");
  }

  return response.json();
};

export const getWorkflowHealth = async (token?: string) => {
  const response = await fetch(`${API_BASE_URL}/api/v1/performance/health`, {
    headers: createHeaders(token),
  });

  if (!response.ok) {
    throw new Error("Failed to fetch workflow health");
  }

  return response.json();
};
