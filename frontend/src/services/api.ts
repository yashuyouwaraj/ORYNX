import type { Workflow } from "@/types/workflow";

export type PageEnvelope<T> = {
  content: T[];
  number?: number;
  size?: number;
  totalElements?: number;
  totalPages?: number;
  first?: boolean;
  last?: boolean;
  [key: string]: unknown;
};

export const DEFAULT_PAGE_SIZE = 20;

const unwrapPageEnvelope = <T>(
  data: T[] | PageEnvelope<T>,
  page: number,
  size: number
): PageEnvelope<T> => {
  if (Array.isArray(data)) {
    return {
      content: data,
      number: page,
      size,
      totalElements: data.length,
      totalPages: data.length === 0 ? 0 : 1,
      first: page === 0,
      last: true,
    };
  }
  if (data && Array.isArray((data as PageEnvelope<T>).content)) {
    return data as PageEnvelope<T>;
  }
  return {
    content: [],
    number: page,
    size,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true,
  };
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

export const getWorkflowsPage = async (
  token?: string,
  page = 0,
  size = DEFAULT_PAGE_SIZE
): Promise<PageEnvelope<Workflow>> => {
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
  return unwrapPageEnvelope(data, page, size);
};

export const getWorkflows = async (
  token?: string,
  page = 0,
  size = DEFAULT_PAGE_SIZE
): Promise<Workflow[]> => {
  const pageData = await getWorkflowsPage(token, page, size);
  return pageData.content;
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

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const getTimelinePage = async <T = any>(
  workflowId: number,
  token?: string,
  page = 0,
  size = DEFAULT_PAGE_SIZE
): Promise<PageEnvelope<T>> => {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/timeline/${workflowId}?page=${page}&size=${size}`,
    {
      headers: createHeaders(token),
    }
  );

  if (!response.ok) {
    throw new Error("Failed to fetch timeline");
  }

  const data = (await response.json()) as T[] | PageEnvelope<T>;
  return unwrapPageEnvelope(data, page, size);
};

export const getTimeline = async (
  workflowId: number,
  token?: string,
  page = 0,
  size = DEFAULT_PAGE_SIZE
) => {
  const pageData = await getTimelinePage(workflowId, token, page, size);
  return pageData.content;
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
