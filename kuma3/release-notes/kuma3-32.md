**Changes since release 31**

kuma3 now follows the desktop fork's build 115 (JSchoreels):

- **Three RWKV model sizes, as on the desktop: Big, Small (default) and Tiny.** Pick one in Settings > kuma3 > RWKV model, or in Deck Options > RWKV > Advanced > Model size (both show the same choice). Big is the most accurate and uses the most memory; Tiny the least. Only the chosen size's models stay installed.
- **Each size has its own RWKV-Instant model.** When your presets use both RWKV-Instant and RWKV-Curve, both models run; with RWKV-Instant only, just the Instant model. **"Use one model for RWKV"** (Settings > kuma3) runs one model for both, with less memory and slightly less accurate Instant predictions.
- Big's models are leak-free now. Small is the desktop's new default.
- **The first start after updating rebuilds the RWKV state once** (new models). On a tablet with 200,000 reviews: Small about 12-20 s, Big about 47 s (about 1.6 minutes with both Big models), in the background.
- New cards mixed in with reviews keep their place when RWKV rebuilds (they stayed at the end before).
- Security updates from the fork (rusqlite, pulldown-cmark, ammonia).
- For apps on the same phone: the `kuma3/sync` provider can start a background sync.

<!-- es -->
**Cambios desde la versión 31**

kuma3 ahora sigue la versión 115 del fork de escritorio (JSchoreels):

- **Tres tamaños de modelo RWKV, como en el escritorio: Grande, Pequeño (por defecto) y Diminuto.** Elige uno en Ajustes > kuma3 > Modelo RWKV, o en Opciones del mazo > RWKV > Advanced > Model size (los dos muestran la misma elección). Grande es el más preciso y el que más memoria usa; Diminuto el que menos. Solo se quedan instalados los modelos del tamaño elegido.
- **Cada tamaño tiene su propio modelo de RWKV-Instant.** Si tus preajustes usan RWKV-Instant y RWKV-Curve, funcionan los dos modelos; con solo RWKV-Instant, solo el modelo Instant. **"Usar un solo modelo para RWKV"** (Ajustes > kuma3) usa un modelo para los dos, con menos memoria y predicciones de Instant un poco menos precisas.
- Los modelos de Grande ahora no tienen fugas. Pequeño es el nuevo predeterminado del escritorio.
- **El primer inicio después de actualizar reconstruye el estado de RWKV una vez** (modelos nuevos). En una tablet con 200.000 repasos: Pequeño unos 12-20 s, Grande unos 47 s (cerca de 1,6 minutos con los dos modelos de Grande), en segundo plano.
- Las tarjetas nuevas mezcladas con los repasos mantienen su lugar cuando RWKV se reconstruye (antes quedaban al final).
- Actualizaciones de seguridad del fork (rusqlite, pulldown-cmark, ammonia).
- Para otras apps del mismo teléfono: el proveedor `kuma3/sync` puede iniciar una sincronización en segundo plano.
