**Changes since release 11**

- Fix: with "Use faster, approximate queue updates" on, the reviewer could stop after one card with "Congratulations" while the deck list still showed cards due. Answering a card sometimes made the RWKV queue update fail, and the queue fell back to the normal due dates.
- Big collections: the RWKV forecast under the deck list (due / near / safe, +1 h, +3 h, tomorrow) and its graph are no longer shown above 3000 review cards. It scored every review card four times on each reload: about 15 s on a 22k-card collection, and opening a deck waited for it.
- Faster answers on big collections: the RWKV state file is saved in the background (66 ms instead of about 3 s on a 22k-card collection).
- For kumapie and kuma3 Skins: they can read today's cards, the review log and the study queue, undo, open kuma3's own screens (note editor, study options) and rate a card that isn't at the top of the queue.
- Privacy: crash reports are never sent; the leaderboard sign-in is kept out of Android backups and device transfers; the leaderboard reply is size-capped. The Lofi music controls only accept kuma3 and trusted apps.
- The forecast graph no longer keeps the deck list in memory after it closes.

---

**Cambios desde la versión 11**

- Arreglo: con "Usar actualizaciones de cola más rápidas y aproximadas" activado, el repaso podía terminar tras una tarjeta con "Felicidades" aunque la lista de mazos aún mostraba tarjetas pendientes. Al responder, la actualización de la cola RWKV a veces fallaba y la cola volvía a las fechas normales.
- Colecciones grandes: el pronóstico RWKV bajo la lista de mazos (due / near / safe, +1 h, +3 h, mañana) y su gráfico ya no se muestran con más de 3000 tarjetas de repaso. Puntuaba cada tarjeta cuatro veces en cada recarga: unos 15 s con 22k tarjetas, y abrir un mazo tenía que esperarlo.
- Respuestas más rápidas en colecciones grandes: el archivo de estado RWKV se guarda en segundo plano (66 ms en vez de unos 3 s con 22k tarjetas).
- Para kumapie y kuma3 Skins: pueden leer las tarjetas de hoy, el historial de repasos y la cola de estudio, deshacer, abrir pantallas de kuma3 (editor de notas, opciones de estudio) y calificar una tarjeta que no está al principio de la cola.
- Privacidad: los informes de errores nunca se envían; el inicio de sesión del leaderboard queda fuera de las copias de seguridad de Android; la respuesta del leaderboard tiene un tamaño máximo. Los controles de música de Lofi solo aceptan kuma3 y apps de confianza.
- El gráfico del pronóstico ya no mantiene la lista de mazos en memoria después de cerrarla.
