**Changes since release 10**

Big collections (tested with 22k cards and 220k reviews on a Pixel 8a):

- Fix: reviewing with RWKV-Instant waited 2 to 2.5 s for each next card. An answer now goes straight into the RWKV state, as on the desktop, instead of rechecking the whole review history. The phone now also follows the preset's "Update the RWKV queue every N answers" and "Use faster, approximate queue updates" options. With approximate updates on, the next card appears in 0.05 to 0.25 s. Both options stay off unless you turn them on (Deck options > RWKV).
- RWKV uses half the memory (1.35 GB to 0.67 GB for that collection), so Android no longer closes other apps to make room. Scores stay within 0.0003 of before.
- Faster start: the RWKV state file is half the size and is loaded in the background (deck list after 1.8 s instead of 6.5 s). Until it is ready, the deck list uses the normal order and refreshes once RWKV is ready. The first start after updating rebuilds the state in the background (about 30 s on a big collection).

---

**Cambios desde la versión 10**

Colecciones grandes (probado con 22k tarjetas y 220k repasos en un Pixel 8a):

- Arreglo: al repasar con RWKV-Instant, cada tarjeta siguiente tardaba 2 a 2,5 s. Ahora cada respuesta entra directamente en el estado RWKV, como en el escritorio, sin volver a revisar todo el historial. El teléfono también respeta las opciones del preset "Actualizar la cola RWKV cada N respuestas" y "Usar actualizaciones de cola más rápidas y aproximadas". Con las aproximadas activadas, la tarjeta siguiente aparece en 0,05 a 0,25 s. Las dos siguen desactivadas salvo que las actives (Opciones del mazo > RWKV).
- RWKV usa la mitad de memoria (de 1,35 GB a 0,67 GB con esa colección), así que Android ya no cierra otras apps para hacer sitio. Las puntuaciones cambian menos de 0,0003.
- Inicio más rápido: el archivo de estado RWKV ocupa la mitad y se carga en segundo plano (lista de mazos en 1,8 s en vez de 6,5 s). Mientras tanto la lista usa el orden normal y se actualiza cuando RWKV está listo. El primer inicio tras actualizar reconstruye el estado en segundo plano (unos 30 s con una colección grande).
