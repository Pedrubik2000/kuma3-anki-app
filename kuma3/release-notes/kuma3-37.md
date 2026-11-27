**Changes since release 36**

- **Fixed a crash shortly after opening kuma3.** If your review history changed while RWKV was getting ready (for example after an undo or a sync), kuma3 could close with "RWKV history changed; rebuilding in the background". Now it keeps the standard order for a moment, rebuilds RWKV in the background, and switches to RWKV's counts when it is ready.

<!-- es -->
**Cambios desde la versión 36**

- **Se corrigió un cierre inesperado poco después de abrir kuma3.** Si tu historial de repasos cambiaba mientras RWKV se preparaba (por ejemplo, después de deshacer o de sincronizar), kuma3 podía cerrarse con "RWKV history changed; rebuilding in the background". Ahora usa el orden normal un momento, reconstruye RWKV en segundo plano y cambia a los números de RWKV cuando está listo.
