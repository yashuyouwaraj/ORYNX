import { create } from "zustand";

type TaskState = {

  taskStatuses: Record<number, string>;

  updateTaskStatus: (
    taskId: number,
    status: string,
  ) => void;
};

export const useTaskStore =
  create<TaskState>((set) => ({

    taskStatuses: {},

    updateTaskStatus: (
      taskId,
      status,
    ) => {

      set((state) => ({

        taskStatuses: {

          ...state.taskStatuses,

          [taskId]: status,
        },
      }));
    },
  }));