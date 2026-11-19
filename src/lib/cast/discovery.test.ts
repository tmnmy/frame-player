import { afterEach, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  invoke: vi.fn(async (command: string, _args?: Record<string, unknown>) => command.endsWith('_devices') ? [] : undefined),
  cast: { discovering: false, devices: [], dlnaDevices: [], dlnaSweeping: false, airplayRoutes: false },
}));

vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
vi.mock('../platform', () => ({ IS_MAC: false }));
vi.mock('./ladder', () => ({ refreshCastPlan: vi.fn(async () => {}) }));
vi.mock('./state.svelte', () => ({ cast: mocks.cast }));

import { startCastDiscovery, stopCastDiscovery } from './discovery';

afterEach(() => {
  stopCastDiscovery();
  stopCastDiscovery('remote');
  vi.clearAllMocks();
});

function calls(command: string) {
  return mocks.invoke.mock.calls.filter(([name]) => name === command).length;
}

it('keeps discovery running until both the desktop picker and the phone have released it', () => {
  startCastDiscovery();
  startCastDiscovery('remote');
  startCastDiscovery('remote');
  expect(calls('cast_discover_start')).toBe(1);
  expect(calls('dlna_discover_start')).toBe(1);

  stopCastDiscovery('remote');
  expect(calls('cast_discover_stop')).toBe(0);
  expect(calls('dlna_discover_stop')).toBe(0);
  expect(mocks.cast.discovering).toBe(true);

  stopCastDiscovery();
  expect(calls('cast_discover_stop')).toBe(1);
  expect(calls('dlna_discover_stop')).toBe(1);
  expect(mocks.cast.discovering).toBe(false);
});
