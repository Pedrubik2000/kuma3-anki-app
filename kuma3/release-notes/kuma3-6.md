**New: kuma3 Anki** (`kuma3-anki-arm64.apk`)

The app now comes as **kuma3 Anki**, a proper release build with its own bear icon (black bear in light mode, white bear in dark mode). It starts faster, scrolls faster and is smaller (64 MB instead of 98 MB). It installs **next to** the older app and keeps its **own collection** in the `kuma3` folder.

**Moving to kuma3 Anki**
1. In the older app, **sync**.
2. Install `kuma3-anki-arm64.apk` and open **kuma3 Anki**.
3. Allow **All files access**, sign in to AnkiWeb and choose **download**.
4. Use only kuma3 Anki from then on. When everything is there, you can uninstall the older app (sync first).
5. The leaderboard is off in a new install: turn it on in Settings > kuma3, then sign in from the deck list menu.

The older app (`kuma3-anki-debug-arm64.apk`) gets this release too, for a while.

**Changes since release 5**
- Faster start: the RWKV state is kept between starts, so opening the app no longer replays the whole review history (on a Tab S7+: about 1 s less, the deck list in 1.3 s in kuma3 Anki).
- The "RWKV ready" message only appears after the state is rebuilt (first start, after an update), not on every start.
- Settings > kuma3: turn the review heatmap, the time per deck, the leaderboard and the "RWKV ready" message on or off.
- Downloads have fixed names now: `.../releases/latest/download/kuma3-anki-arm64.apk` always gives the newest kuma3 Anki.
- Releases are built and signed on GitHub from the public source.

---

**Nuevo: kuma3 Anki** (`kuma3-anki-arm64.apk`)

La app ahora viene como **kuma3 Anki**: una versión final con su propio icono de oso (oso negro en modo claro, oso blanco en modo oscuro). Arranca y se mueve más rápido y pesa menos (64 MB en vez de 98 MB). Se instala **junto a** la app anterior y guarda su **propia colección** en la carpeta `kuma3`.

**Cómo pasar a kuma3 Anki**
1. En la app anterior, **sincroniza**.
2. Instala `kuma3-anki-arm64.apk` y abre **kuma3 Anki**.
3. Permite **Acceso a todos los archivos**, inicia sesión en AnkiWeb y elige **descargar**.
4. A partir de ahí usa solo kuma3 Anki. Cuando esté todo, puedes desinstalar la app anterior (sincroniza antes).
5. El leaderboard viene apagado: actívalo en Ajustes > kuma3 e inicia sesión desde el menú de la lista de mazos.

**Cambios desde la versión 5**
- Arranque más rápido: el estado de RWKV se guarda entre aperturas y ya no se recalcula todo el historial.
- El mensaje "RWKV ready" solo sale cuando se reconstruye el estado, no en cada apertura.
- Ajustes > kuma3: activa o desactiva el heatmap, el tiempo por mazo, el leaderboard y el mensaje de RWKV.
- Enlace de descarga fijo: `.../releases/latest/download/kuma3-anki-arm64.apk` siempre da el kuma3 Anki más nuevo.
