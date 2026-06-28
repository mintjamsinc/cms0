/**
 * Composables Index
 *
 * Reusable functions that bridge services and UI components with reactive state.
 */

// Async utilities
export {
  useAsync,
  usePaginated,
  type AsyncState,
  type UseAsyncOptions,
  type UseAsyncReturn,
  type PaginatedState,
  type PageInfo,
  type Connection,
  type UsePaginatedOptions,
  type UsePaginatedReturn,
} from './use-async.js';

// Content composables
export {
  useNode,
  useNodeList,
  useSearch,
  useNodeMutations,
  useXPath,
  type UseNodeOptions,
  type UseNodeReturn,
  type UseNodeListOptions,
  type UseNodeListReturn,
  type UseSearchOptions,
  type UseSearchReturn,
  type UseNodeMutationsReturn,
  type UseXPathReturn,
} from './use-content.js';

// BPM composables
export {
  useTask,
  useTaskList,
  useTaskCounts,
  useTaskMutations,
  useProcessDefinitions,
  useProcessInstance,
  useProcessOperations,
  type UseTaskOptions,
  type UseTaskReturn,
  type UseTaskListOptions,
  type UseTaskListReturn,
  type UseTaskCountsOptions,
  type UseTaskCountsReturn,
  type UseTaskMutationsReturn,
  type UseProcessDefinitionsReturn,
  type UseProcessInstanceOptions,
  type UseProcessInstanceReturn,
  type UseProcessOperationsReturn,
} from './use-bpm.js';

// EIP composables
export {
  useRoute,
  useRouteList,
  useRouteStatistics,
  type UseRouteOptions,
  type UseRouteReturn,
  type UseRouteListOptions,
  type UseRouteListReturn,
  type UseRouteStatisticsReturn,
} from './use-eip.js';

// Localization composable
export {
  createLocalizationSnapshot,
  refreshLocalization,
  handleLocalizationMessage,
  translate,
  formatNumber,
  formatCurrency,
  formatDate,
  type LocalizationSnapshot,
} from './use-localization.js';
