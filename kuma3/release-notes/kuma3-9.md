**Changes since release 8**

Based on the FSRS-7 fork's [build 100](https://github.com/JSchoreels/anki/releases/tag/26.09.3%2Bfsrs7.build.100) (was build 96). Use desktop build 100 together with this release, so both schedule the same way.

- **Deck options:** pick the scheduler with buttons (FSRS-6, FSRS-7, RWKV-Curve, RWKV-Instant), with FSRS-7 as the fallback that writes due dates for Instant. The RWKV forecast is now under Desired retention.
- **Learning cards wait too:** "Minimum other reviews" and "Minimum seconds before a repeat" (now in one row) also apply to learning and relearning cards.
- **Same-day reviews:** "Allow same day review for (re)learning steps" now also controls FSRS-7 and RWKV-Curve intervals; turned off, those intervals are at least one day.
- **Safer full downloads** from AnkiWeb (checked before they replace the collection), using less memory; faster media checks.

---

**Cambios desde la versión 8**

Basada en la [build 100](https://github.com/JSchoreels/anki/releases/tag/26.09.3%2Bfsrs7.build.100) del fork FSRS-7 (antes la build 96). Usa la build 100 en el escritorio junto con esta versión, para que los dos programen igual.

- **Opciones del mazo:** el programador se elige con botones (FSRS-6, FSRS-7, RWKV-Curve, RWKV-Instant), con FSRS-7 como respaldo que escribe las fechas para Instant. La previsión RWKV ahora está debajo de Retención deseada.
- **Las tarjetas en aprendizaje también esperan:** "Minimum other reviews" y "Minimum seconds before a repeat" (ahora en una fila) también se aplican a tarjetas en aprendizaje y reaprendizaje.
- **Repasos el mismo día:** "Allow same day review for (re)learning steps" ahora también controla los intervalos de FSRS-7 y RWKV-Curve; si está apagado, esos intervalos son de al menos un día.
- **Descargas completas más seguras** desde AnkiWeb (se comprueban antes de reemplazar la colección) y con menos memoria; revisión de medios más rápida.
