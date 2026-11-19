import { afterEach, beforeEach, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  cast: { active: false, transport: 'cast', state: 'idle', time: 0, audioSwitching: false },
  player: { filePath: '/media/current.mp4', timePos: 25, duration: 60, loopMode: 'one', paused: false, audioTracks: [] },
  session: { currentDevice: null, advancing: false, pollTimer: null as ReturnType<typeof setInterval> | null },
  receiverState: 'playing',
  invoke: vi.fn(async (name: string, _args?: Record<string, unknown>): Promise<unknown> => {
    if (name === 'cast_status') {
      return { state: mocks.receiverState, time: 60, duration: 60, volume: 1, fetches: 1 };
    }
    return undefined;
  }),
  neighbour: vi.fn(),
  playEntry: vi.fn(async (entry: { path: string }) => { mocks.player.filePath = entry.path; }),
  playlist: { autoAdvance: false },
}));

vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
vi.mock('tauri-plugin-libmpv-api', () => ({ setProperty: vi.fn(async () => {}) }));
vi.mock('../airplay-choice', () => ({}));
vi.mock('../history.svelte', () => ({ isPrivatePath: () => false }));
vi.mock('../i18n.svelte', () => ({ t: (key: string) => key }));
vi.mock('../osd.svelte', () => ({ showOsd: vi.fn() }));
vi.mock('../player.svelte', () => ({ isNetworkSource: () => false, player: mocks.player }));
vi.mock('../thumbs.svelte', () => ({ suspendThumbs: vi.fn() }));
vi.mock('../platform', () => ({ IS_MAC: false }));
vi.mock('../playlist.svelte', () => ({
  neighbour: mocks.neighbour, playEntry: mocks.playEntry, playlist: mocks.playlist,
}));
vi.mock('./airplay', () => ({}));
vi.mock('./controls', () => ({ transportCommand: (verb: string) => mocks.cast.transport + '_' + verb }));
vi.mock('./devices', () => ({ deviceKind: () => 'tv', transportFor: () => 'cast' }));
vi.mock('./handback', () => ({
  endCast: vi.fn(async () => { mocks.cast.active = false; }),
  stopPoll: () => {
    if (mocks.session.pollTimer !== null) clearInterval(mocks.session.pollTimer);
    mocks.session.pollTimer = null;
  },
}));
vi.mock('./ladder', () => ({
  castVerdict: async () => ({ kind: 'direct', transcodeAudio: false }),
  resolveCastSource: async (src: string) => ({ src }),
}));
vi.mock('./prefs', () => ({ castMode: () => 'auto', castAudioMode: () => 'auto' }));
vi.mock('./prepare', () => ({}));
vi.mock('./record', () => ({ noteSession: vi.fn() }));
vi.mock('./state.svelte', () => ({ AIRPLAY_DEVICE: {}, cast: mocks.cast, session: mocks.session }));

import { castAdvance, castCurrentFile } from './session';
import type { TvDevice } from './state.svelte';

const device: TvDevice = {
  key: 'receiver', name: 'Living room', ip: '192.168.0.10', model: 'Receiver',
  cast: { id: 'receiver', name: 'Living room', ip: '192.168.0.10', model: 'Receiver', port: 8009 },
};

beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  mocks.cast.active = false;
  mocks.cast.state = 'idle';
  mocks.receiverState = 'playing';
  mocks.player.filePath = '/media/current.mp4';
});

afterEach(() => {
  vi.clearAllTimers();
  mocks.session.pollTimer = null;
  vi.useRealTimers();
});

it('replays the ended item from zero on the existing receiver even when queue auto-advance is off', async () => {
  expect(await castCurrentFile(device)).toBe(true);
  await vi.advanceTimersByTimeAsync(500);
  mocks.receiverState = 'ended';
  await vi.advanceTimersByTimeAsync(500);

  const packets = mocks.invoke.mock.calls.filter(([name]) => name === 'cast_load');
  expect(packets.map(([, args]) => ({ path: args?.path, position: args?.position }))).toEqual([
    { path: '/media/current.mp4', position: 25 },
    { path: '/media/current.mp4', position: 0 },
  ]);
  expect(mocks.invoke.mock.calls.filter(([name]) => name === 'cast_connect')).toHaveLength(1);
  expect(mocks.playEntry).not.toHaveBeenCalled();
  expect(mocks.neighbour).not.toHaveBeenCalled();
});

it('keeps manual next on the queue while Repeat One is enabled', async () => {
  expect(await castCurrentFile(device)).toBe(true);
  const next = { index: 1, path: '/media/next.mp4', title: 'Next', named: true };
  mocks.neighbour.mockReturnValueOnce(next);
  expect(await castAdvance(1)).toBe(true);

  expect(mocks.neighbour).toHaveBeenCalledExactlyOnceWith(1);
  expect(mocks.playEntry).toHaveBeenCalledExactlyOnceWith(next);
  expect(mocks.invoke).toHaveBeenLastCalledWith('cast_load', expect.objectContaining({ path: next.path }));
});
