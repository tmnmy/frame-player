import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import {
  cast, castAudioMode, castAudioModeAvailable, castCurrentFile, disconnectCast,
  setCastAudioMode, startCastDiscovery, stopCastDiscovery, type TvDevice,
} from '$lib/cast.svelte';
import { displayName } from '$lib/format';
import { isPrivatePath } from '$lib/history.svelte';
import type { MessageKey } from '$lib/i18n.svelte';
import { advance, openEntry, playback, seekFraction, setVolume, toggleMute, togglePause } from '$lib/playback.svelte';
import { applyLoopMode, isNetworkSource, player, type LoopMode, type Track } from '$lib/player.svelte';
import { isAudioPath } from '$lib/player/files';
import { playlist, type PlaylistEntry } from '$lib/playlist.svelte';
import { parseTorrentUrl } from '$lib/source';
import { selectTrack } from '$lib/tracks.svelte';

type Pairing = { name: string; code: string };
type Preferences = { enabled: boolean; pairedName: string | null };
type Command =
  | { action: 'pause' | 'mute'; value: boolean }
  | { action: 'seek' | 'volume' | 'audio'; value: number }
  | { action: 'subtitle'; value: number | null }
  | { action: 'audio_mode'; value: 'auto' | 'stereo' }
  | { action: 'cast_search'; value: boolean }
  | { action: 'cast_start' | 'queue_play'; value: string }
  | { action: 'repeat'; value: LoopMode }
  | { action: 'cast_stop' | 'next' | 'previous' };

export const remote = $state({
  on: false,
  enabled: false,
  pairedName: null as string | null,
  busy: false,
  pairing: null as Pairing | null,
  error: null as MessageKey | null,
});

function setRemoteError(key: MessageKey, error: unknown) {
  console.warn('Remote control:', error);
  remote.error = key;
}

function updatePreferences(preferences: Preferences) {
  remote.enabled = preferences.enabled;
  remote.pairedName = preferences.pairedName;
}

function canSelectSubtitles() {
  // The upstream player only sends subtitles through supported AirPlay or DLNA routes.
  return !cast.remote || (cast.transport === 'airplay' && playback.can.subChoice);
}

// Limit the size of a JSON string in UTF-8, including escaped quotes and slashes.
function remoteLabel(value: string, maxBytes: number): string {
  let label = '';
  let bytes = 0;
  for (const char of value.replace(/[\p{Cc}\p{Cs}\p{Bidi_Control}]/gu, '').trim()) {
    const code = char.codePointAt(0)!;
    const width = char === '"' || char === '\\' ? 2
      : code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4;
    if (bytes + width > maxBytes) break;
    bytes += width;
    label += char;
  }
  return label;
}

function trackSnapshot(tracks: Track[]) {
  return tracks.slice(0, 64).map(({ id, label, selected }) => ({
    id, label: remoteLabel(label, 120), selected,
  }));
}

// An opaque label for comparing snapshots, independent of secure-context APIs.
// The per-page prefix keeps the labels distinct when the webview is reloaded.
const sourceSession = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
let currentSource: string | null = null;
let sourceRevision = 0;

const MAX_REMOTE_QUEUE_ITEMS = 300;
// The phone's status reader accepts 65,536 bytes, including the response wrapper.
const REMOTE_STATUS_BUDGET = 60_000;
const statusEncoder = new TextEncoder();
type PublishedQueueItem = { id: string; path: string };
let publishedQueue = new Map<number, PublishedQueueItem>();
let queueSerial = 0;

function queueTitle(entry: PlaylistEntry, maxBytes: number): string {
  const title = entry.title.trim();
  const isPath = title === entry.path || /^[\\/]/.test(title)
    || /\b(?:[a-z]:[\\/]|[a-z][a-z\d+.-]*:\/\/|magnet:\?)/i.test(title);
  const label = isPath ? (isNetworkSource(entry.path) ? '' : displayName(entry.path)) : title;
  return remoteLabel(label || 'Media ' + (entry.index + 1), maxBytes);
}

function queueSnapshot(statusBytes: number) {
  const entries = player.hasFile ? playlist.entries.filter((entry) => !isPrivatePath(entry.path)) : [];
  const current = entries.findIndex((entry) => entry.index === player.playlistPos);
  const first = Math.max(0, Math.min(current - MAX_REMOTE_QUEUE_ITEMS / 2, entries.length - MAX_REMOTE_QUEUE_ITEMS));
  const visible = entries.slice(first, first + MAX_REMOTE_QUEUE_ITEMS);
  const rowBudget = Math.floor((REMOTE_STATUS_BUDGET - statusBytes - 128) / Math.max(1, visible.length));
  const targets = new Map<number, PublishedQueueItem>();
  const queue = [];
  for (const entry of visible) {
    const previous = publishedQueue.get(entry.index);
    const id = previous?.path === entry.path ? previous.id : sourceSession + '-q' + (++queueSerial).toString(36);
    const row = { id, title: '', current: entry.index === player.playlistPos };
    const labelBudget = Math.min(120, rowBudget - statusEncoder.encode(JSON.stringify(row)).length - 1);
    if (labelBudget < 1) break;
    row.title = queueTitle(entry, labelBudget);
    targets.set(entry.index, { id, path: entry.path });
    queue.push(row);
  }
  publishedQueue = targets;
  return { queue, queueTotal: entries.length };
}

function openRemoteQueueEntry(id: string) {
  const target = [...publishedQueue].find(([, item]) => item.id === id);
  if (!target) return;
  const [index, item] = target;
  // Resolve the selection against the latest queue. Reorders or a missing entry
  // must never apply a previously published ID to a different media file.
  const entry = playlist.entries.find((candidate) => candidate.index === index && candidate.path === item.path);
  if (entry && !isPrivatePath(entry.path)) openEntry(entry);
}

const CAST_SEARCH_MS = 25_000;
const MAX_CAST_TARGETS = 20;
let castTargetSerial = 0;
let remoteCastStart: { cancelled: boolean } | null = null;
let castIssue: 'not_available' | 'target_gone' | 'start_failed' | null = null;
let castSearch: {
  path: string;
  expires: number;
  timer: ReturnType<typeof setTimeout>;
  ids: Map<string, string>;
} | null = null;

function canWatchOnTv(): boolean {
  const path = player.filePath;
  return cast.active || (player.hasFile && !!path && (!isNetworkSource(path) || !!parseTorrentUrl(path)));
}

function closeCastSearch() {
  if (!castSearch) return;
  clearTimeout(castSearch.timer);
  castSearch = null;
  stopCastDiscovery('remote');
}

function currentCastSearch(path: string | null) {
  if (castSearch && (castSearch.path !== path || !canWatchOnTv() || cast.active
      || performance.now() >= castSearch.expires)) {
    closeCastSearch();
  }
  return castSearch;
}

function openCastSearch() {
  const path = player.filePath;
  if (!path || !canWatchOnTv() || cast.active || remoteCastStart) {
    castIssue = 'not_available';
    return;
  }
  if (castSearch) clearTimeout(castSearch.timer);
  castIssue = null;
  castSearch = {
    path,
    expires: performance.now() + CAST_SEARCH_MS,
    timer: setTimeout(() => {
      closeCastSearch();
      publishSnapshot();
    }, CAST_SEARCH_MS),
    ids: new Map(),
  };
  startCastDiscovery('remote');
}

function targetIdentity(device: TvDevice): string {
  return JSON.stringify([device.key, device.ip]);
}

function castSnapshot(path: string | null) {
  const search = currentCastSearch(path);
  const ids = new Map<string, string>();
  const castTargets = search ? cast.tvs.slice(0, MAX_CAST_TARGETS).map((device) => {
    const key = targetIdentity(device);
    const id = search.ids.get(key) ?? sourceSession + '-' + (++castTargetSerial).toString(36);
    ids.set(key, id);
    const name = [...device.name.replace(/[\p{Cc}\p{Bidi_Control}]/gu, '').trim()].slice(0, 64).join('');
    return { id, name: name || 'TV' };
  }) : [];
  if (search) search.ids = ids;
  return {
    castAvailable: canWatchOnTv(),
    castActive: cast.active,
    castSearching: search !== null,
    castStarting: remoteCastStart !== null,
    castTargets,
    castIssue,
  };
}

async function startRemoteCast(id: string) {
  if (!canWatchOnTv() || cast.active || remoteCastStart) {
    castIssue = 'not_available';
    publishSnapshot();
    return;
  }
  const search = currentCastSearch(player.filePath);
  const device = search && cast.tvs.find((candidate) => search.ids.get(targetIdentity(candidate)) === id);
  if (!device) {
    castIssue = 'target_gone';
    publishSnapshot();
    return;
  }
  closeCastSearch();
  castIssue = null;
  const attempt = { cancelled: false };
  remoteCastStart = attempt;
  publishSnapshot();
  try {
    if (!await castCurrentFile(device) && !attempt.cancelled) castIssue = 'start_failed';
  } catch (error) {
    console.warn('Remote TV playback failed:', error);
    if (!attempt.cancelled) castIssue = 'start_failed';
  } finally {
    remoteCastStart = null;
    publishSnapshot();
  }
}

function publishSnapshot() {
  if (!remote.on) return;
  const path = player.hasFile ? player.filePath : null;
  if (path !== currentSource) {
    currentSource = path;
    sourceRevision++;
  }
  const privateSource = path !== null && isPrivatePath(path);
  // This argument remains inside the desktop IPC. Only opaque IDs and the
  // JPEG from a local, non-private video are part of the remote protocol.
  const previewPath = path && !privateSource && !isNetworkSource(path) && !isAudioPath(path) ? path : null;
  const status = {
    ready: player.hasFile,
    sourceId: path === null ? null : sourceSession + '-' + sourceRevision,
    title: privateSource || !player.displayTitle ? null : remoteLabel(player.displayTitle, 150),
    output: cast.deviceName ? remoteLabel(cast.deviceName, 150) : null,
    ...castSnapshot(path),
    paused: playback.paused,
    position: playback.position,
    duration: playback.duration,
    speed: playback.remote ? 1 : player.speed || 1,
    volume: Math.round(Math.min(100, playback.volume)),
    muted: playback.muted,
    volumeAdjustable: playback.can.volume,
    audioMode: castAudioMode(),
    audioModeAvailable: castAudioModeAvailable(),
    audioSwitching: cast.audioSwitching,
    audio: playback.can.trackChoice ? trackSnapshot(player.audioTracks) : [],
    subtitles: canSelectSubtitles() ? trackSnapshot(player.subTracks) : [],
    repeat: player.loopMode,
  };
  void invoke('remote_snapshot', {
    previewPath,
    snapshot: { ...status, ...queueSnapshot(statusEncoder.encode(JSON.stringify(status)).length) },
  }).catch((error) => console.warn('remote snapshot:', error));
}

function apply({ action, ...command }: Command) {
  if (!remote.on) return;
  if (action === 'cast_search' && 'value' in command) {
    if (command.value === true) openCastSearch();
    else closeCastSearch();
    publishSnapshot();
    return;
  }
  if (action === 'cast_start' && 'value' in command && typeof command.value === 'string') {
    void startRemoteCast(command.value);
    return;
  }
  if (action === 'cast_stop') {
    if (cast.active) {
      if (remoteCastStart) remoteCastStart.cancelled = true;
      castIssue = null;
      void disconnectCast()
        .catch((error) => console.warn('Remote TV disconnect failed:', error))
        .finally(publishSnapshot);
    }
    return;
  }
  if (action === 'audio_mode' && 'value' in command) {
    if (castAudioModeAvailable() && (command.value === 'auto' || command.value === 'stereo')) {
      void setCastAudioMode(command.value).catch(console.warn);
    }
    return;
  }
  if (!player.hasFile) return;
  const value = 'value' in command ? command.value : null;
  switch (action) {
    case 'pause':
      if (value !== playback.paused) togglePause();
      break;
    case 'seek':
      if (typeof value === 'number' && playback.duration > 0) {
        seekFraction(Math.min(100, value / playback.duration * 100));
      }
      break;
    case 'volume':
      if (typeof value === 'number' && playback.can.volume) setVolume(value);
      break;
    case 'mute':
      if (playback.can.volume && value !== playback.muted) toggleMute();
      break;
    case 'audio': {
      const track = player.audioTracks.find((candidate) => candidate.id === value);
      if (track && playback.can.trackChoice) selectTrack('audio', track);
      break;
    }
    case 'subtitle': {
      const track = player.subTracks.find((candidate) => candidate.id === value);
      if (canSelectSubtitles() && (track || value === null)) selectTrack('sub', track ?? null);
      break;
    }
    case 'repeat':
      if (value === 'off' || value === 'one' || value === 'all') {
        applyLoopMode(value);
        publishSnapshot();
      }
      break;
    case 'queue_play':
      if (typeof value === 'string') openRemoteQueueEntry(value);
      break;
    case 'next':
      advance(1);
      break;
    case 'previous':
      advance(-1);
      break;
  }
}

export function mountRemote() {
  // Register before the server can announce itself. The preferences panel mounts
  // independently, so approval remains reachable when settings are closed.
  const events = Promise.all([
    listen<Pairing>('remote-pair-request', ({ payload }) => {
      remote.pairing = payload;
    }, { target: 'main' }),
    listen<Command>('remote-command', ({ payload }) => apply(payload), { target: 'main' }),
  ]);
  let disposed = false;
  const timer = window.setInterval(publishSnapshot, 500);
  let retry: number | undefined;
  const ensureEnabled = () => {
    if (remote.enabled && !remote.on && !remote.busy) void setRemoteEnabled(true);
  };
  void events.then(async () => {
    if (disposed) return;
    updatePreferences(await invoke<Preferences>('remote_preferences'));
    if (disposed) return;
    ensureEnabled();
    // The laptop may boot before Wi-Fi has an address.
    retry = window.setInterval(ensureEnabled, 5000);
  }).catch((error) => setRemoteError('remote.error_settings', error));
  return () => {
    disposed = true;
    closeCastSearch();
    window.clearInterval(timer);
    window.clearInterval(retry);
    void events.then((unlisten) => unlisten.forEach((off) => off())).catch(console.warn);
  };
}

async function setRemoteEnabled(enable: boolean) {
  if (remote.busy) return;
  remote.busy = true;
  remote.error = null;
  try {
    if (enable) {
      updatePreferences(await invoke<Preferences>('remote_enable'));
      remote.on = true;
      publishSnapshot();
    } else {
      await invoke('remote_disable');
      closeCastSearch();
      castIssue = null;
      remote.pairing = null;
      remote.on = false;
      remote.enabled = false;
    }
  } catch (error) {
    const key = error === 'remote_no_ipv4'
      ? 'remote.error_network'
      : enable ? 'remote.error_start' : 'remote.error_stop';
    setRemoteError(key, error);
  } finally {
    remote.busy = false;
  }
}

export async function toggleRemote() {
  await setRemoteEnabled(!remote.enabled);
}

export async function forgetRemote() {
  try {
    await invoke('remote_forget');
    closeCastSearch();
    castIssue = null;
    remote.pairedName = null;
    remote.pairing = null;
    remote.error = null;
  } catch (error) {
    setRemoteError('remote.error_forget', error);
  }
}

export async function decideRemote(allow: boolean) {
  const request = remote.pairing;
  if (!request) return;
  remote.pairing = null;
  try {
    updatePreferences(await invoke<Preferences>('remote_decide', { code: request.code, allow }));
  } catch (error) {
    setRemoteError(error === 'pairing_expired' ? 'remote.error_pairing_expired' : 'remote.error_pairing', error);
  }
}
