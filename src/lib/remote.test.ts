import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  listeners: new Map<string, (event: { payload: unknown }) => void>(),
  invoke: vi.fn(async (_command: string, _args?: Record<string, unknown>) => ({ enabled: false, pairedName: null })),
  devices: [
    { key: 'tv-a', ip: '192.168.1.180', name: 'Chromecast Salon', model: 'Chromecast' },
    { key: 'tv-b', ip: '192.168.1.181', name: 'Freebox', model: 'Freebox' },
  ],
  cast: { active: false, deviceName: null as string | null, tvs: [] as Array<{ key: string; ip: string; name: string; model: string }> },
  player: {
    hasFile: true, filePath: '/Films/a.mkv', displayTitle: 'Film', playlistPos: 0, speed: 1,
    loopMode: 'off' as 'off' | 'one' | 'all',
    audioTracks: [] as Array<{ id: number; label: string; selected: boolean }>,
    subTracks: [] as Array<{ id: number; label: string; selected: boolean }>,
  },
  playlist: { entries: [] as Array<{ index: number; path: string; title: string; named: boolean }> },
  openEntry: vi.fn(),
  applyLoopMode: vi.fn(),
  privatePath: vi.fn((path: string) => path.startsWith('/Private/')),
  startDiscovery: vi.fn(),
  stopDiscovery: vi.fn(),
  castFile: vi.fn(async (_device: { key: string }) => true),
  disconnect: vi.fn(async () => {}),
}));

vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
vi.mock('@tauri-apps/api/event', () => ({
  listen: vi.fn(async (name: string, handler: (event: { payload: unknown }) => void) => {
    mocks.listeners.set(name, handler);
    return () => mocks.listeners.delete(name);
  }),
}));
vi.mock('$lib/cast.svelte', () => ({
  cast: mocks.cast,
  castAudioMode: () => 'auto',
  castAudioModeAvailable: () => false,
  castCurrentFile: mocks.castFile,
  disconnectCast: mocks.disconnect,
  setCastAudioMode: vi.fn(),
  startCastDiscovery: mocks.startDiscovery,
  stopCastDiscovery: mocks.stopDiscovery,
}));
vi.mock('$lib/history.svelte', () => ({ isPrivatePath: mocks.privatePath }));
vi.mock('$lib/playlist.svelte', () => ({ playlist: mocks.playlist }));
vi.mock('$lib/playback.svelte', () => ({
  playback: { paused: true, position: 20, duration: 60, volume: 50, muted: false, can: { volume: true, trackChoice: true } },
  advance: vi.fn(),
  openEntry: mocks.openEntry,
  seekFraction: vi.fn(),
  setVolume: vi.fn(),
  toggleMute: vi.fn(),
  togglePause: vi.fn(),
}));
vi.mock('$lib/player.svelte', () => ({
  isNetworkSource: (path: string) => path.startsWith('https:'),
  player: mocks.player,
  applyLoopMode: mocks.applyLoopMode,
}));
vi.mock('$lib/player/files', () => ({ isAudioPath: () => false }));
vi.mock('$lib/tracks.svelte', () => ({ selectTrack: vi.fn() }));

import { mountRemote, remote } from './remote.svelte';

type CastSnapshot = {
  castAvailable: boolean;
  castSearching: boolean;
  castStarting: boolean;
  castTargets: Array<{ id: string; name: string }>;
  castIssue: string | null;
  repeat: 'off' | 'one' | 'all';
  queue: Array<{ id: string; title: string; current: boolean }>;
  queueTotal: number;
};

function snapshot(): CastSnapshot {
  const latest = mocks.invoke.mock.calls.filter(([name]) => name === 'remote_snapshot').at(-1);
  return latest?.[1]?.snapshot as CastSnapshot;
}

function command(action: string, value?: unknown) {
  const accept = mocks.listeners.get('remote-command');
  if (!accept) throw new Error('Remote command listener was not mounted.');
  accept({ payload: { action, value } });
}

let dispose: () => void;

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers();
  vi.stubGlobal('window', { setInterval, clearInterval });
  mocks.cast.active = false;
  mocks.cast.deviceName = null;
  mocks.cast.tvs = [...mocks.devices];
  mocks.player.hasFile = true;
  mocks.player.filePath = '/Films/a.mkv';
  mocks.player.playlistPos = 0;
  mocks.player.audioTracks = [];
  mocks.player.subTracks = [];
  mocks.player.speed = 1;
  mocks.player.loopMode = 'off';
  mocks.applyLoopMode.mockImplementation((mode) => { mocks.player.loopMode = mode; });
  mocks.playlist.entries = [];
  remote.on = true;
  dispose = mountRemote();
});

afterEach(async () => {
  remote.on = false;
  dispose();
  await Promise.resolve();
  await Promise.resolve();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('paired remote TV selection', () => {
  it('opens discovery only on request and closes it when the lease expires', async () => {
    await vi.advanceTimersByTimeAsync(500);
    expect(snapshot()).toMatchObject({ castAvailable: true, castSearching: false, castTargets: [] });
    expect(mocks.startDiscovery).not.toHaveBeenCalled();

    command('cast_search', true);
    expect(mocks.startDiscovery).toHaveBeenCalledWith('remote');
    expect(snapshot()).toMatchObject({ castSearching: true, castIssue: null });
    const targets = snapshot().castTargets;
    expect(targets.map((target) => target.name)).toEqual(['Chromecast Salon', 'Freebox']);
    expect(targets.every((target) => /^[a-z0-9-]+$/.test(target.id))).toBe(true);
    expect(JSON.stringify(targets)).not.toContain('192.168.');

    await vi.advanceTimersByTimeAsync(25_000);
    expect(mocks.stopDiscovery).toHaveBeenCalledWith('remote');
    expect(snapshot()).toMatchObject({ castSearching: false, castTargets: [] });
    command('cast_start', targets[0].id);
    expect(mocks.castFile).not.toHaveBeenCalled();
    expect(snapshot().castIssue).toBe('target_gone');
  });

  it('invalidates a selection when a different file is opened', () => {
    command('cast_search', true);
    const [target] = snapshot().castTargets;
    mocks.player.filePath = '/Films/b.mkv';
    command('cast_start', target.id);

    expect(mocks.castFile).not.toHaveBeenCalled();
    expect(mocks.stopDiscovery).toHaveBeenCalledWith('remote');
    expect(snapshot()).toMatchObject({ castSearching: false, castIssue: 'target_gone' });
  });

  it('starts only a discovered choice and reports when existing playback rejects it', async () => {
    mocks.castFile.mockResolvedValueOnce(false);
    command('cast_search', true);
    const [target] = snapshot().castTargets;
    command('cast_start', target.id);

    expect(mocks.castFile).toHaveBeenCalledExactlyOnceWith(mocks.devices[0]);
    expect(snapshot()).toMatchObject({ castSearching: false, castStarting: true, castIssue: null });
    await Promise.resolve();
    expect(snapshot()).toMatchObject({ castStarting: false, castIssue: 'start_failed' });
  });

  it('skips unsupported stream sources and can stop an existing cast', async () => {
    mocks.player.filePath = 'https://example.invalid/live';
    command('cast_search', true);
    expect(mocks.startDiscovery).not.toHaveBeenCalled();
    expect(snapshot()).toMatchObject({ castAvailable: false, castIssue: 'not_available' });

    mocks.cast.active = true;
    command('cast_stop');
    expect(mocks.disconnect).toHaveBeenCalledOnce();
    await vi.advanceTimersByTimeAsync(500);
    expect(snapshot()).toMatchObject({ castAvailable: true, castIssue: null });
  });
});


describe('paired remote queue and repeat', () => {
  it('reports repeat immediately after setting it through the player', async () => {
    await vi.advanceTimersByTimeAsync(500);
    expect(snapshot().repeat).toBe('off');
    for (const mode of ['one', 'all', 'off']) {
      command('repeat', mode);
      expect(mocks.applyLoopMode).toHaveBeenLastCalledWith(mode);
      expect(snapshot().repeat).toBe(mode);
    }
    command('repeat', 'invalid');
    expect(mocks.applyLoopMode).toHaveBeenCalledTimes(3);
  });

  it('opens only published entries that still match the live queue', async () => {
    const movie = { index: 0, path: '/Films/a.mkv', title: 'First film', named: true };
    const next = { index: 1, path: '/Films/b.mkv', title: 'Second film', named: true };
    const privateMovie = { index: 2, path: '/Private/c.mkv', title: 'Secret title', named: true };
    const link = { index: 3, path: 'https://example.invalid/a?secret=x', title: 'https://example.invalid/a?secret=x', named: false };
    mocks.playlist.entries = [movie, next, privateMovie, link];
    await vi.advanceTimersByTimeAsync(500);

    const { queue, queueTotal } = snapshot();
    expect(queueTotal).toBe(3);
    expect(queue.map(({ title, current }) => ({ title, current }))).toEqual([
      { title: 'First film', current: true },
      { title: 'Second film', current: false },
      { title: 'Media 4', current: false },
    ]);
    expect(queue.every(({ id }) => /^[a-z0-9-]{1,64}$/.test(id))).toBe(true);
    expect(JSON.stringify(queue)).not.toMatch(/\/Films|Secret title|example\.invalid/);
    await vi.advanceTimersByTimeAsync(500);
    expect(snapshot().queue).toEqual(queue);

    command('queue_play', queue[1].id);
    expect(mocks.openEntry).toHaveBeenCalledExactlyOnceWith(next);
    mocks.playlist.entries = [movie, { ...privateMovie, index: 1 }, { ...next, index: 2 }, link];
    command('queue_play', queue[1].id);
    expect(mocks.openEntry).toHaveBeenCalledTimes(1);
  });

  it('keeps a full routine queue inside the phone status reader budget', async () => {
    const label = '😀"\\漢'.repeat(80);
    mocks.player.audioTracks = Array.from({ length: 64 }, (_, id) => ({ id, label, selected: false }));
    mocks.player.subTracks = mocks.player.audioTracks;
    mocks.playlist.entries = Array.from({ length: 300 }, (_, index) => ({
      index, path: '/Films/movie-' + index + '.mkv', title: label, named: true,
    }));
    mocks.player.playlistPos = 150;
    await vi.advanceTimersByTimeAsync(500);

    const state = snapshot();
    expect(state.queueTotal).toBe(300);
    expect(state.queue).toHaveLength(300);
    expect(state.queue[150]?.current).toBe(true);
    expect(Buffer.byteLength(JSON.stringify({ ok: true, state }))).toBeLessThan(65_536);
  });
});