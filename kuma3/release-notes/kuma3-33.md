**Changes since release 32**

- **"Compare RWKV with FSRS" works on the phone** (Deck Options > RWKV > Advanced). It failed with "unhandled method: startRwkvWorkload": the desktop runs it in its Python, which the phone doesn't have. kuma3 now does the same steps (same sampled cards, one-year background reviews, answer model from RWKV's recorded predictions) with the same simulation, in the background. On a tablet: 250 cards in about 25 s, "Compare both answer models" in about 1 minute. Reviews use the standard order until it finishes.
- It needs RWKV-Curve, as on the desktop: with RWKV-Instant and an FSRS fallback the page says so. Pick RWKV-Curve as the scheduler or as RWKV-Instant's fallback and save to use it.

<!-- es -->
**Cambios desde la versión 32**

- **"Compare RWKV with FSRS" funciona en el teléfono** (Opciones del mazo > RWKV > Advanced). Fallaba con "unhandled method: startRwkvWorkload": el escritorio lo hace con su Python, que el teléfono no tiene. Ahora kuma3 hace los mismos pasos (las mismas tarjetas de muestra, un año de repasos de fondo, el modelo de respuestas a partir de las predicciones guardadas de RWKV) con la misma simulación, en segundo plano. En una tablet: 250 tarjetas en unos 25 s, "Compare both answer models" en cerca de 1 minuto. Mientras tanto los repasos usan el orden normal.
- Necesita RWKV-Curve, como en el escritorio: con RWKV-Instant y FSRS como respaldo la página lo dice. Elige RWKV-Curve como planificador o como respaldo de RWKV-Instant y guarda para usarlo.
