import type {
  V2ProductAvailabilityDocument,
  V2ProductCapability,
} from '../api/agent';

export const V2_PRODUCT_AVAILABILITY_FORMAT_VERSION = 1;
export const V2_PRODUCT_CAPABILITIES = [
  'literature.search',
  'project.read-analysis',
  'project.candidate',
] as const satisfies readonly V2ProductCapability[];

export type V2ProductAvailabilityStatus = 'loading' | 'ready' | 'failed';

export interface V2ProductAvailabilityState {
  status: V2ProductAvailabilityStatus;
  enabled: boolean;
  capabilities: readonly V2ProductCapability[];
}

export const V2_PRODUCT_AVAILABILITY_LOADING: V2ProductAvailabilityState = Object.freeze({
  status: 'loading',
  enabled: false,
  capabilities: Object.freeze([]),
});

export const V2_PRODUCT_AVAILABILITY_FAILED: V2ProductAvailabilityState = Object.freeze({
  status: 'failed',
  enabled: false,
  capabilities: Object.freeze([]),
});

const allowedCapabilities = new Set<string>(V2_PRODUCT_CAPABILITIES);

export function parseV2ProductAvailability(
  value: unknown,
): V2ProductAvailabilityState {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('v2-availability-invalid-document');
  }
  const document = value as Partial<V2ProductAvailabilityDocument>;
  if (document.formatVersion !== V2_PRODUCT_AVAILABILITY_FORMAT_VERSION
      || typeof document.enabled !== 'boolean'
      || !Array.isArray(document.capabilities)) {
    throw new Error('v2-availability-invalid-document');
  }
  const capabilities = document.capabilities;
  if (capabilities.some((capability) => typeof capability !== 'string'
      || !allowedCapabilities.has(capability))
      || new Set(capabilities).size !== capabilities.length) {
    throw new Error('v2-availability-invalid-capabilities');
  }
  return Object.freeze({
    status: 'ready',
    enabled: document.enabled,
    capabilities: Object.freeze([...capabilities]) as readonly V2ProductCapability[],
  });
}

export async function loadV2ProductAvailability(
  read: () => Promise<unknown>,
): Promise<V2ProductAvailabilityState> {
  try {
    return parseV2ProductAvailability(await read());
  } catch {
    return V2_PRODUCT_AVAILABILITY_FAILED;
  }
}

export function isV2CapabilityAvailable(
  state: V2ProductAvailabilityState,
  capability: V2ProductCapability,
) {
  return state.status === 'ready'
    && state.enabled
    && state.capabilities.includes(capability);
}

export function isV2ControlDisabled(
  state: V2ProductAvailabilityState,
  capability: V2ProductCapability,
  busy = false,
) {
  return busy || !isV2CapabilityAvailable(state, capability);
}

export function v2AvailabilityLabel(
  state: V2ProductAvailabilityState,
  capability: V2ProductCapability,
) {
  if (state.status === 'loading') return 'V2 availability loading';
  return isV2CapabilityAvailable(state, capability)
    ? 'V2 available'
    : 'V2 unavailable';
}
