<script lang="ts">
  import { t } from '$lib/i18n.svelte';
  import { decideRemote, remote } from '$lib/remote.svelte';

  let dialog: HTMLDialogElement | undefined = $state();

  $effect(() => {
    if (!dialog) return;
    if (remote.pairing && !dialog.open) dialog.showModal();
    if (!remote.pairing && dialog.open) dialog.close();
  });
</script>

<dialog bind:this={dialog} oncancel={() => void decideRemote(false)} aria-labelledby="remote-title">
  {#if remote.pairing}
    <h2 id="remote-title">{t('remote.pair_title')}</h2>
    <p>{t('remote.pair_request', { name: remote.pairing.name || t('remote.phone') })}</p>
    <p>{t('remote.pair_check')}</p>
    <p class="code">{remote.pairing.code}</p>
    <div class="actions">
      <button onclick={() => void decideRemote(false)}>{t('remote.deny')}</button>
      <button onclick={() => void decideRemote(true)}>{t('remote.allow')}</button>
    </div>
  {/if}
</dialog>

<style>
  dialog {
    max-width: min(28rem, 90vw);
    padding: 1.5rem;
    border: 1px solid #555;
    border-radius: 12px;
    background: #202124;
    color: #fff;
  }
  dialog::backdrop { background: #0009; }
  h2 { margin-top: 0; font-size: 1.15rem; }
  .code { font: 1.8rem monospace; letter-spacing: 0.04em; overflow-wrap: anywhere; }
  .actions { display: flex; justify-content: flex-end; gap: .75rem; }
  button { padding: .5rem .8rem; }
</style>
