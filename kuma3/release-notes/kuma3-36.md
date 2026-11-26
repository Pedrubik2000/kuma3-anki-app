**Changes since release 35**

- **RWKV no longer switches itself off after one failed load.** If RWKV failed to load once in the background (for example when the phone was short of memory), kuma3 turned RWKV off without saying so. Deck Options then read "RWKV is not active on this device", and your review counts went down one card at a time, as with the standard scheduler. RWKV only came back after you saved Deck Options, sometimes several times. Now kuma3 tries again by itself once.
- **Deck Options now tells you what RWKV is doing.** While RWKV loads, it shows "RWKV is loading on this device (n / m reviews)" and switches to the forecast by itself when RWKV is ready. If RWKV can't load, it shows the reason and "Save the options to retry."
- **Compare RWKV with FSRS no longer restarts over and over** when RWKV can't load for the comparison. It finishes with FSRS only.

<!-- es -->
**Cambios desde la versión 35**

- **RWKV ya no se apaga solo después de un fallo al cargar.** Si RWKV fallaba una vez al cargarse en segundo plano (por ejemplo, cuando al teléfono le faltaba memoria), kuma3 apagaba RWKV sin avisar. Las opciones del mazo mostraban "RWKV is not active on this device" y el número de repasos bajaba de una en una tarjeta, como con el planificador normal. RWKV solo volvía al guardar las opciones del mazo, a veces varias veces. Ahora kuma3 lo vuelve a intentar solo una vez.
- **Las opciones del mazo ahora dicen qué está haciendo RWKV.** Mientras RWKV carga, muestran "RWKV is loading on this device (n / m reviews)" y cambian solas al pronóstico cuando RWKV está listo. Si RWKV no puede cargar, muestran el motivo y "Save the options to retry."
- **Comparar RWKV con FSRS ya no se reinicia una y otra vez** cuando RWKV no puede cargar para la comparación. Termina solo con FSRS.
