**Changes since release 33**

- **kuma3 no longer keeps a phone busy when it's left on the deck list.** A phone left on the charger with kuma3 open all night got hot. Two things could keep working with nobody there:
  - The small bar under the sync button, while media syncs: it now stops after a minute (the notification still shows the progress), so a media sync that never ends doesn't redraw the screen all night.
  - The wait for RWKV to finish a rebuild: if a rebuild stopped halfway, kuma3 kept checking, kept the "Preparing RWKV" notification and kept the phone awake. It now gives up after 10 minutes without progress.

<!-- es -->
**Cambios desde la versión 33**

- **kuma3 ya no mantiene ocupado el teléfono cuando se queda en la lista de mazos.** Un teléfono que pasó la noche cargando con kuma3 abierto se calentó. Dos cosas podían seguir trabajando sin nadie delante:
  - La barrita bajo el botón de sincronizar, mientras se sincronizan los archivos: ahora se detiene al minuto (la notificación sigue mostrando el progreso), así una sincronización que no termina no redibuja la pantalla toda la noche.
  - La espera a que RWKV termine de reconstruirse: si una reconstrucción se paraba a medias, kuma3 seguía comprobando, mantenía la notificación "Preparing RWKV" y el teléfono despierto. Ahora se rinde tras 10 minutos sin avance.
