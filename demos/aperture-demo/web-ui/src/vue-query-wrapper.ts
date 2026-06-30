// Minimal wrapper to replace @tanstack/vue-query with a lightweight implementation
// Provides the same named exports used in the codebase so TypeScript can compile

import { ref, Ref } from 'vue';
import { useFetch } from '@vueuse/core';

export interface QueryResult<T> {
  data: Ref<T | undefined>;
  error: Ref<any>;
  isLoading: Ref<boolean>;
  isError: Ref<boolean>;
  refetch: () => Promise<void>;
}

/**
 * Simple useQuery implementation compatible with the object's option signature used in the codebase.
 * Accepts an object with `queryKey` (ignored) and `queryFn` returning a Promise.
 * Executes immediately unless `immediate: false` is passed.
 */
export function useQuery<T>(options: {
  queryKey?: unknown;
  queryFn: () => Promise<T>;
  immediate?: boolean;
}): QueryResult<T> {
  const data = ref<T>();
  const error = ref<any>(null);
  const isLoading = ref(false);
  const isError = ref(false);

  const run = async () => {
    isLoading.value = true;
    isError.value = false;
    error.value = null;
    try {
      data.value = await options.queryFn();
    } catch (e) {
      error.value = e;
      isError.value = true;
    } finally {
      isLoading.value = false;
    }
  };

  if (options.immediate ?? true) {
    void run();
  }

  return { data, error, isLoading, isError, refetch: run };
}

/** Stub for useQueryClient – returns an empty object. */
export function useQueryClient() {
  return {
    invalidateQueries: async (_options?: any) => {
      // no‑op stub for query client invalidate
    },
  };
}

/** Stub VueQueryPlugin – used in main.ts for global install. */
export const VueQueryPlugin = {
  install(app: any) {
    // no‑op – wrapper functions are imported directly where needed
  },
};
export class QueryClient {
  constructor(_options?: any) {}
};
