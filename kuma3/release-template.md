**Which file?**
- `kuma3-anki-arm64.apk`: **kuma3 Anki**, the app to use. Fast release build with its own icon, and its own collection folder (`kuma3`), so it installs next to AnkiDroid.
- `kuma3-anki-debug-arm64.apk`: the older debug app (`com.ichi2.anki.debug`), for people who already have it. It updates in place; it will stop being published once everyone has moved to kuma3 Anki.

**What is in it**
AnkiDroid 2.26.0alpha2 built against the JSchoreels FSRS-7 fork of Anki (26.09.3+fsrs7, build 96), with RWKV-Instant running on the device. Unofficial personal build: not from the AnkiDroid team, the Anki project or the fork's author.
- RWKV-Instant: presets with "Use RWKV-Instant to choose review cards" pick review cards by predicted recall, as on the fork's desktop. RWKV-Curve and "reschedule" are not implemented.
- FSRS-7, with the fork's own repair of the state that AnkiWeb sync drops (`s_int`, `s_fast`).
- Deck options page working on phones, with "Rebuild RWKV State" and a status line.
- Deck list extras, each switchable in Settings > kuma3: review heatmap, time answered today per deck, leaderboard (the desktop Anki Leaderboard add-on's server; off until you turn it on).
- Custom study button on the finished-deck screen.

**Before you install**
- arm64 phones and tablets only.
- kuma3 Anki keeps its own collection: open it, allow "All files access", sign in to AnkiWeb and choose download. If you also keep another Anki app, study in one at a time and sync before switching.
- Sync before uninstalling. Keep AnkiWeb sync on so your collection has a copy elsewhere.

**En español**
- `kuma3-anki-arm64.apk` es **kuma3 Anki**, la app recomendada: más rápida, con su propio icono y su propia carpeta (`kuma3`), así que se instala junto a AnkiDroid.
- `kuma3-anki-debug-arm64.apk` es la app anterior, para quien ya la tiene (se actualiza encima).
- Al abrir kuma3 Anki: permite "Acceso a todos los archivos", inicia sesión en AnkiWeb y elige descargar. Si usas dos apps de Anki, estudia en una sola y sincroniza antes de cambiar.
- Versión personal no oficial (no es del equipo de AnkiDroid ni de Anki). Solo para móviles y tablets arm64.
