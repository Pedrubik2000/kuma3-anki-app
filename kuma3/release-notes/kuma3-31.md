**Changes since release 30**

JSchoreels' fixes that make kuma3 behave like the desktop fork after a sync (merged from kuma3-anki-core [#2](https://github.com/Pedrubik2000/kuma3-anki-core/pull/2), [#3](https://github.com/Pedrubik2000/kuma3-anki-core/pull/3), [#4](https://github.com/Pedrubik2000/kuma3-anki-core/pull/4), [#6](https://github.com/Pedrubik2000/kuma3-anki-core/pull/6), [#7](https://github.com/Pedrubik2000/kuma3-anki-core/pull/7), [#8](https://github.com/Pedrubik2000/kuma3-anki-core/pull/8), [#9](https://github.com/Pedrubik2000/kuma3-anki-core/pull/9) and [kuma3-anki-app#3](https://github.com/Pedrubik2000/kuma3-anki-app/pull/3)):

- **The Small RWKV model is now SM72**, JSchoreels' leak-free model, the one newer desktop builds use. If you use Small, kuma3 installs it and removes the old SM56 file.
- **The first start after updating rebuilds the RWKV state once** (its saved format changed). On a phone with 200,000 reviews it takes 25-50 seconds, in the background.
- **Rules from the Dynamic Desired Retention add-on now apply in kuma3**: its search, field and grade rules, synced from the desktop. They need the add-on version that saves them in the collection: sync the desktop, save the rule table there, sync again, then sync kuma3. A grade rule's exact target is now kept as the card's desired retention.
- **Presets from the Dynamic Preset Selection add-on** no longer stop RWKV from preparing the review history.
- **A card reimported as new** no longer counts its old reviews in RWKV, as on the desktop.
- **Filtered decks**: answers are recorded with the same review kind as the desktop, and a preview answer no longer changes the card's stability with RWKV-Curve.
- **"Update the RWKV queue every"** for a deck with subdecks comes from the preset they share (JSchoreels' final version of [kuma3-anki-core#1](https://github.com/Pedrubik2000/kuma3-anki-core/pull/1)).
- `RWKV.md` explains which RWKV settings stay on each device and the differences with the desktop that remain.

<!-- es -->
**Cambios desde la versión 30**

Arreglos de JSchoreels para que kuma3 se comporte como el fork de escritorio después de sincronizar (integrados desde kuma3-anki-core [#2](https://github.com/Pedrubik2000/kuma3-anki-core/pull/2), [#3](https://github.com/Pedrubik2000/kuma3-anki-core/pull/3), [#4](https://github.com/Pedrubik2000/kuma3-anki-core/pull/4), [#6](https://github.com/Pedrubik2000/kuma3-anki-core/pull/6), [#7](https://github.com/Pedrubik2000/kuma3-anki-core/pull/7), [#8](https://github.com/Pedrubik2000/kuma3-anki-core/pull/8), [#9](https://github.com/Pedrubik2000/kuma3-anki-core/pull/9) y [kuma3-anki-app#3](https://github.com/Pedrubik2000/kuma3-anki-app/pull/3)):

- **El modelo RWKV pequeño ahora es SM72**, el modelo sin fugas de JSchoreels, el que usan las versiones nuevas de escritorio. Si usas el pequeño, kuma3 lo instala y borra el archivo SM56 viejo.
- **El primer inicio después de actualizar reconstruye el estado de RWKV una vez** (cambió su formato guardado). En un teléfono con 200.000 repasos tarda 25-50 segundos, en segundo plano.
- **Las reglas del complemento Dynamic Desired Retention ahora se aplican en kuma3**: sus reglas de búsqueda, de campo y de botón, sincronizadas desde el escritorio. Hace falta la versión del complemento que las guarda en la colección: sincroniza el escritorio, guarda allí la tabla de reglas, sincroniza otra vez y luego sincroniza kuma3. El objetivo exacto de una regla de botón ahora se guarda como la retención deseada de la tarjeta.
- **Los preajustes del complemento Dynamic Preset Selection** ya no impiden que RWKV prepare el historial de repasos.
- **Una tarjeta reimportada como nueva** ya no cuenta sus repasos viejos en RWKV, como en el escritorio.
- **Mazos filtrados**: las respuestas se guardan con el mismo tipo de repaso que en el escritorio, y una respuesta de vista previa ya no cambia la estabilidad de la tarjeta con RWKV-Curve.
- **"Update the RWKV queue every"** en un mazo con submazos sale del preajuste que comparten (la versión final de JSchoreels de [kuma3-anki-core#1](https://github.com/Pedrubik2000/kuma3-anki-core/pull/1)).
- `RWKV.md` explica qué ajustes de RWKV se quedan en cada dispositivo y las diferencias con el escritorio que siguen.
