import type { Workflow } from "@/types/workflow";

const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8082";

export const getWorkflows = async (): Promise<Workflow[]> => {
    const response = await fetch(`${API_BASE_URL}/api/v1/workflows`)

    if(!response.ok){
        throw new Error("Failed to fetch workflows");
    }

    return response.json()
}

export const getAnalytics = async()=>{
    const response = await fetch(`${API_BASE_URL}/api/v1/analytics`)

    if(!response.ok){
        throw new Error("Failed to fetch analytics");
    }

    return response.json()
}

export const getTimeline = async (workflowId: number)=>{
    const response = await fetch(`${API_BASE_URL}/api/v1/timeline/${workflowId}`)

    if(!response.ok){
        throw new Error("Failed to fetch timeline");
    }

    return response.json()
}

export const getPerformance = async ()=>{
    const response = await fetch(`${API_BASE_URL}/api/v1/performance`)

    if(!response.ok){
        throw new Error("Failed to fetch performance");
    }

    return response.json()
}

export const getPerformanceSummary = async ()=>{
    const response = await fetch(`${API_BASE_URL}/api/v1/performance/summary`)

    if(!response.ok){
        throw new Error("Failed to fetch performance summary");
    }

    return response.json()
}