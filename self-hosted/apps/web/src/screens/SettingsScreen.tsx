import {
  Check,
  Clipboard,
  Cloud,
  Database,
  Download,
  KeyRound,
  LockKeyhole,
  RefreshCw,
  RotateCcw,
  Save,
  Server,
  ShieldCheck,
  Trash2,
} from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import type { SyncConfiguration, UserProfile } from "../domain";
import { useAppStore } from "../store/AppStore";

function encodePairingBundle(configuration: SyncConfiguration): string {
  const payload = JSON.stringify({
    version: 1,
    endpoint: configuration.endpoint,
    accessToken: configuration.accessToken,
    encryptionKey: configuration.encryptionKey,
    keyId: configuration.keyId,
  });
  const bytes = new TextEncoder().encode(payload);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

function decodePairingBundle(bundle: string): Partial<SyncConfiguration> {
  const normalized = bundle.trim().replaceAll("-", "+").replaceAll("_", "/");
  const binary = atob(normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "="));
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  const parsed = JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>;
  if (parsed.version !== 1) throw new Error("Unsupported pairing bundle version");
  return {
    endpoint: typeof parsed.endpoint === "string" ? parsed.endpoint : "",
    accessToken: typeof parsed.accessToken === "string" ? parsed.accessToken : "",
    encryptionKey: typeof parsed.encryptionKey === "string" ? parsed.encryptionKey : "",
    keyId: typeof parsed.keyId === "string" ? parsed.keyId : "",
    enabled: true,
    cursor: "",
  };
}

export function SettingsScreen() {
  const {
    profile,
    records,
    updateProfile,
    syncConfiguration,
    syncState,
    syncMessage,
    saveSyncConfiguration,
    createPairingKey,
    syncNow,
    resetDemo,
    clearDiary,
  } = useAppStore();
  const [profileDraft, setProfileDraft] = useState(profile);
  const [profileDirty, setProfileDirty] = useState(false);
  const [syncDraft, setSyncDraft] = useState(syncConfiguration);
  const [pairingInput, setPairingInput] = useState("");
  const [copied, setCopied] = useState(false);
  const [pairingError, setPairingError] = useState("");

  useEffect(() => setSyncDraft(syncConfiguration), [syncConfiguration]);
  useEffect(() => {
    if (!profileDirty) setProfileDraft(profile);
  }, [profile, profileDirty]);

  function scrollToSection(id: string) {
    document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  async function saveProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next: UserProfile = {
      ...profileDraft,
      displayName: profileDraft.displayName.trim() || "You",
    };
    await updateProfile(next);
    setProfileDraft(next);
    setProfileDirty(false);
  }

  function updateProfileDraft<K extends keyof UserProfile>(key: K, value: UserProfile[K]) {
    setProfileDraft((current) => ({ ...current, [key]: value }));
    setProfileDirty(true);
  }

  async function saveSync(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSyncDraft(await saveSyncConfiguration(syncDraft));
  }

  async function copyPairingBundle() {
    if (!syncDraft.encryptionKey || !syncDraft.accessToken) return;
    await navigator.clipboard.writeText(encodePairingBundle(syncDraft));
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1800);
  }

  async function importPairingBundle() {
    try {
      const decoded = decodePairingBundle(pairingInput);
      if (syncDraft.encryptionKey
          && decoded.encryptionKey
          && syncDraft.encryptionKey !== decoded.encryptionKey) {
        throw new Error("This browser already belongs to a different encrypted sync workspace.");
      }
      const next = { ...syncDraft, ...decoded };
      setSyncDraft(await saveSyncConfiguration(next));
      setPairingInput("");
      setPairingError("");
    } catch (error) {
      setPairingError(error instanceof Error
          && error.message === "This browser already belongs to a different encrypted sync workspace."
        ? error.message
        : "That pairing bundle is not valid.");
    }
  }

  function exportData() {
    const blob = new Blob([JSON.stringify({ app: "Fud AI Web", formatVersion: 1, exportedAt: new Date().toISOString(), records }, null, 2)], { type: "application/json" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = `fud-ai-web-backup-${new Date().toISOString().slice(0, 10)}.json`;
    link.click();
    URL.revokeObjectURL(link.href);
  }

  async function confirmClearDiary() {
    const confirmed = window.confirm(syncDraft.enabled
      ? "Delete every diary, progress, Coach, and workout entry from this encrypted sync workspace? This cannot be undone."
      : "Delete every diary, progress, Coach, and workout entry stored in this browser? This cannot be undone.");
    if (confirmed) await clearDiary();
  }

  return (
    <div className="standard-screen settings-screen">
      <header className="standard-header"><div><p>Private by default</p><h1>Settings</h1></div></header>

      <div className="settings-layout">
        <nav className="settings-index" aria-label="Settings sections">
          <button type="button" onClick={() => scrollToSection("goals")}>Goals</button><button type="button" onClick={() => scrollToSection("sync")}>Encrypted Sync</button><button type="button" onClick={() => scrollToSection("pair")}>Pair a Device</button><button type="button" onClick={() => scrollToSection("data")}>Data</button><button type="button" onClick={() => scrollToSection("privacy")}>Privacy</button>
        </nav>
        <div className="settings-sections">
          <section className="settings-section" id="goals">
            <div className="settings-section-title"><span><Save /></span><div><h2>Profile & Goals</h2><p>These values drive the home overview and local Coach.</p></div></div>
            <form className="form-stack" onSubmit={saveProfile}>
              <label className="field">Display name<input name="displayName" value={profileDraft.displayName} onChange={(event) => updateProfileDraft("displayName", event.target.value)} /></label>
              <div className="form-grid three"><label className="field">Calories<input name="calories" type="number" min="1" value={profileDraft.calories} onChange={(event) => updateProfileDraft("calories", Number(event.target.value))} /></label><label className="field">Protein (g)<input name="protein" type="number" min="1" value={profileDraft.protein} onChange={(event) => updateProfileDraft("protein", Number(event.target.value))} /></label><label className="field">Carbs (g)<input name="carbs" type="number" min="1" value={profileDraft.carbs} onChange={(event) => updateProfileDraft("carbs", Number(event.target.value))} /></label></div>
              <div className="form-grid three"><label className="field">Fat (g)<input name="fat" type="number" min="1" value={profileDraft.fat} onChange={(event) => updateProfileDraft("fat", Number(event.target.value))} /></label><label className="field">Fiber (g)<input name="fiber" type="number" min="1" value={profileDraft.fiber} onChange={(event) => updateProfileDraft("fiber", Number(event.target.value))} /></label><label className="field">Water (ml)<input name="waterGoalMl" type="number" min="1" value={profileDraft.waterGoalMl} onChange={(event) => updateProfileDraft("waterGoalMl", Number(event.target.value))} /></label></div>
              <button className="secondary-button align-end" type="submit">Save goals</button>
            </form>
          </section>

          <section className="settings-section" id="sync">
            <div className="settings-section-title"><span><Cloud /></span><div><h2>Encrypted Sync</h2><p>The server stores opaque ciphertext. Your encryption key stays in this browser.</p></div></div>
            <form className="form-stack" onSubmit={saveSync}>
              <label className="toggle-row"><span><strong>Enable sync</strong><small>Keep local data available offline and copy encrypted changes to your server.</small></span><input type="checkbox" disabled={syncState === "syncing"} checked={syncDraft.enabled} onChange={(event) => setSyncDraft((current) => ({ ...current, enabled: event.target.checked }))} /></label>
              <label className="field"><span>Server URL <small>Cloudflare, Vercel, or Docker</small></span><div className="input-with-icon"><Server /><input type="url" disabled={syncState === "syncing"} placeholder="https://fud.example.com" value={syncDraft.endpoint} onChange={(event) => setSyncDraft((current) => ({ ...current, endpoint: event.target.value }))} /></div></label>
              <label className="field"><span>Access token <small>Configured on your server</small></span><div className="input-with-icon"><KeyRound /><input type="password" disabled={syncState === "syncing"} autoComplete="off" value={syncDraft.accessToken} onChange={(event) => setSyncDraft((current) => ({ ...current, accessToken: event.target.value }))} /></div></label>
              <div className="key-row"><div><LockKeyhole /><span><strong>{syncDraft.encryptionKey ? "Pairing key ready" : "No pairing key"}</strong><small>{syncDraft.keyId || "Create a key before your first sync."}</small></span></div>{syncDraft.encryptionKey ? <span className="key-locked">Key rotation is intentionally disabled</span> : <button className="secondary-button" type="button" disabled={syncState === "syncing"} onClick={() => void (async () => { const next = await createPairingKey(syncDraft); setSyncDraft(next); })()}>Create key</button>}</div>
              <div className={`sync-result sync-${syncState}`}><span className="sync-dot" /><div><strong>{syncState === "syncing" ? "Syncing…" : syncState === "error" ? "Could not sync" : "Ready"}</strong><small>{syncMessage}</small></div></div>
              <div className="button-row"><button className="secondary-button" type="submit" disabled={syncState === "syncing"}>Save connection</button><button className="primary-button" type="button" disabled={!syncDraft.enabled || syncState === "syncing"} onClick={() => void (async () => { const saved = await saveSyncConfiguration(syncDraft); setSyncDraft(saved); await syncNow(saved); })()}><RefreshCw /> Sync now</button></div>
            </form>
          </section>

          <section className="settings-section" id="pair">
            <div className="settings-section-title"><span><KeyRound /></span><div><h2>Pair a Device</h2><p>Transfer the server address, access token, and encryption key directly. Treat this code like a password.</p></div></div>
            <div className="pairing-actions"><button className="secondary-button" type="button" disabled={!syncDraft.encryptionKey || !syncDraft.accessToken} onClick={() => void copyPairingBundle()}>{copied ? <Check /> : <Clipboard />}{copied ? "Copied" : "Copy pairing bundle"}</button></div>
            <div className="pairing-import"><label className="field">Import pairing bundle<textarea rows={3} disabled={syncState === "syncing"} value={pairingInput} onChange={(event) => setPairingInput(event.target.value)} placeholder="Paste a pairing bundle from another Fud AI device" /></label><button className="secondary-button" type="button" disabled={!pairingInput.trim() || syncState === "syncing"} onClick={() => void importPairingBundle()}>Import</button>{pairingError ? <p className="field-error">{pairingError}</p> : null}</div>
          </section>

          <section className="settings-section" id="data">
            <div className="settings-section-title"><span><Database /></span><div><h2>Local Data</h2><p>{records.filter((record) => !record.deleted).length} active records are stored in this browser.</p></div></div>
            <div className="data-actions"><button className="secondary-button" type="button" onClick={exportData}><Download /> Export data</button>{import.meta.env.DEV ? <button className="secondary-button" type="button" onClick={() => void resetDemo()}><RotateCcw /> Restore demo data</button> : null}<button className="danger-button" type="button" onClick={() => void confirmClearDiary()}><Trash2 /> Clear diary</button></div>
          </section>

          <section className="settings-section privacy-section" id="privacy">
            <div className="settings-section-title"><span><ShieldCheck /></span><div><h2>Privacy</h2><p>Local-first is the default, not a fallback.</p></div></div>
            <ul><li>Your diary works without an account or sync server.</li><li>Sync records are encrypted before leaving this browser.</li><li>The sync server never receives your decryption key or AI-provider keys.</li><li>Browser data does not include HealthKit or Health Connect unless a native app explicitly syncs it later.</li></ul>
          </section>
        </div>
      </div>
    </div>
  );
}
