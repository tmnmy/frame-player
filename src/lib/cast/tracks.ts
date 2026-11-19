/** A track picked in the player while a television plays: each transport answers it its own way. */

import { invoke } from '@tauri-apps/api/core';

import { isTextSub } from '../airplay-choice';
import { isPrivatePath } from '../history.svelte';
import { t } from '../i18n.svelte';
import { showOsd } from '../osd.svelte';
import { player, type Track } from '../player.svelte';
import { airplaySwitch } from './airplay';
import { endCast } from './handback';
import { CAST_AUDIO_COPY } from './ladder';
import { castAudioMode, castCacheCapGb, rememberCastAudioMode, type CastAudioMode } from './prefs';
import { prepareForCast, prepareHls } from './prepare';
import { cast, session } from './state.svelte';

export function castAudioModeAvailable(): boolean {
  if (!cast.active) return true;
  return cast.transport === 'cast' && !session.sessionHls && !!session.castSrcPath
    && (cast.state === 'playing' || cast.state === 'paused')
    && player.audioTracks.some((track) => track.selected && !track.external);
}

export async function setCastAudioMode(mode: CastAudioMode): Promise<boolean> {
  if (!castAudioModeAvailable() || cast.audioSwitching) return false;
  if (!cast.active) {
    rememberCastAudioMode(mode);
    cast.audioMode = mode;
    return true;
  }
  if (mode === cast.audioMode && mode === castAudioMode()) return true;
  const track = player.audioTracks.find((track) => track.selected && !track.external);
  if (!track || !await reloadCastAudio(track, mode)) return false;
  rememberCastAudioMode(mode);
  cast.audioMode = mode;
  return true;
}

/** The track used by the TV follows the selected output mode. */
export async function castSwitchAudio(track: Track): Promise<void> {
  if (!cast.remote || !session.castSrcPath) return;
  if (cast.transport === 'dlna') {
    showOsd(t('cast.track_on_tv'));
    return;
  }
  if (cast.transport === 'airplay') {
    await airplaySwitch({ audio: track });
    return;
  }
  if (await reloadCastAudio(track, cast.audioMode)) showOsd(track.label);
}

async function reloadCastAudio(track: Track, mode: CastAudioMode): Promise<boolean> {
  const src = session.castSrcPath;
  if (!cast.remote || !src || session.advancing || cast.audioSwitching || track.external) return false;
  const audioIndex = player.audioTracks.filter((candidate) => !candidate.external).indexOf(track);
  if (audioIndex < 0) return false;

  const codec = track.codec?.toLowerCase() ?? null;
  const stereo = mode === 'stereo';
  const transcode = stereo || !(codec && CAST_AUDIO_COPY.includes(codec));
  const channels = track.channels ?? 6;
  const hidden = isPrivatePath(src);
  const previous = session.preparedPath;
  const hls = session.sessionHls;
  const epoch = ++session.audioSwitchEpoch;
  const current = () => session.audioSwitchEpoch === epoch && cast.active
    && cast.transport === 'cast' && session.castSrcPath === src;
  cast.audioSwitching = true;
  let reloading = false;
  let dispatched = false;
  let paused = cast.paused;
  const failure = async () => {
    if (!current()) return;
    if (dispatched) {
      // The new URL displaced the old registration. If it was not accepted,
      // recover playback on the PC instead of leaving the remote on Loading.
      await endCast({ osd: t('cast.err_load'), resumeLocal: true, resumePaused: paused });
    } else {
      showOsd(t(reloading ? 'cast.err_load' : 'cast.prepare_failed'));
    }
  };
  try {
    // Keep playing the old file while the new audio is prepared. Read the
    // TV's pause and position only at the moment of handover.
    const prepared = hls
      ? await prepareHls(src, transcode, audioIndex, channels, hls === 'fmp4')
      : await prepareForCast(src, transcode, audioIndex, channels, stereo);
    if (!current() || session.advancing) return false;
    const latest = await invoke<{ state: string; time: number }>('cast_status');
    if (!current() || session.advancing) return false;
    if (!['playing', 'paused', 'buffering'].includes(latest.state)) return false;
    paused = latest.state === 'paused';
    reloading = true;
    session.advancing = true;
    const confirmation = await invoke<number>('cast_load', {
      path: prepared,
      position: latest.time,
      title: hidden ? null : player.displayTitle,
      hidden,
      hls,
      autoplay: !paused,
      verifyPlayback: true,
    });
    dispatched = true;
    if (!current()) return false;
    if (!hls) {
      session.preparedPath = prepared;
      session.preparedHidden = hidden;
      if ((hidden || castCacheCapGb() === 0) && previous && previous !== prepared) {
        void invoke('cast_forget_prepared', { path: previous }).catch(() => {});
      }
    }
    const deadline = Date.now() + 14_000;
    while (current() && Date.now() < deadline) {
      const status = await invoke<{ state: string; confirmed_load?: number | null }>('cast_status');
      if (!current()) return false;
      if (status.confirmed_load === confirmation && ['playing', 'paused'].includes(status.state)) {
        cast.state = status.state;
        return true;
      }
      if (['error', 'stopped', 'idle'].includes(status.state)) break;
      await new Promise((resolve) => setTimeout(resolve, 300));
    }
    await failure();
    return false;
  } catch (e) {
    console.warn('cast audio reload failed:', e);
    await failure();
    return false;
  } finally {
    if (session.audioSwitchEpoch === epoch) {
      cast.audioSwitching = false;
      if (reloading) session.advancing = false;
    }
  }
}

/// A subtitle picked while a television plays. Only AirPlay's HLS rendition
/// carries one there; an image subtitle cannot go, and is said so.
export async function castSwitchSub(track: Track | null): Promise<void> {
  if (cast.remote && cast.transport === 'dlna') {
    await dlnaSwitchSub(track);
    return;
  }
  if (!cast.remote || cast.transport !== 'airplay') return;
  if (track && !isTextSub(track)) {
    showOsd(t('cast.airplay_sub_image'));
    return;
  }
  await airplaySwitch({ sub: track });
}

/**
 * A subtitle picked in the player while a DLNA renderer plays. An external
 * one is news to the television, so the file goes again with it, from where
 * the television is — the one way to tell a renderer about a subtitle is the
 * metadata of a load. Anything else is the television's own to switch: the
 * file's subtitles are in its menu already, and so is turning them off.
 */
async function dlnaSwitchSub(track: Track | null): Promise<void> {
  const src = session.castSrcPath;
  const next = track?.external && track.path && isTextSub(track) ? track.path : null;
  if (!next || !src) {
    showOsd(t('cast.sub_on_tv'));
    return;
  }
  if (next === session.dlnaSubPath) return;
  const hidden = isPrivatePath(src);
  session.dlnaSubPath = next;
  // The load begins with a Stop, and the poll must not read that as the
  // viewer stopping the film — the same guard as moving to the next episode.
  session.advancing = true;
  try {
    await invoke('dlna_load', {
      path: src,
      position: cast.time,
      title: hidden ? null : player.displayTitle,
      hidden,
      subtitle: next,
    });
    showOsd(track!.label);
  } catch (e) {
    console.warn('dlna subtitle switch failed:', e);
    session.advancing = false;
    if (cast.active) await endCast({ osd: t('cast.err_load'), resumeLocal: true });
  } finally {
    session.advancing = false;
  }
}
