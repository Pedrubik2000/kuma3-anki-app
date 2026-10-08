**Changes since release 9**

- Fix: on tablets, tapping a deck in the deck list froze the screen for about half a second (twice when the deck had nothing due). The study panel no longer waits on the main thread, and the RWKV forecast, time per deck and heatmap are only recomputed when something they show changed (a review, a sync, an edit, an options change, a new day; the forecast at most a minute old).

---

**Cambios desde la versión 9**

- Arreglo: en tablets, tocar un mazo en la lista congelaba la pantalla cerca de medio segundo (dos veces si el mazo no tenía nada pendiente). El panel de estudio ya no espera en el hilo principal, y la previsión RWKV, el tiempo por mazo y el heatmap solo se recalculan cuando cambia algo que muestran (un repaso, una sincronización, una edición, un cambio de opciones, un día nuevo; la previsión como mucho con un minuto de antigüedad).
