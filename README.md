# ZebraRFID — Inventario RFID para TC22 + RFD4030

App Android para inventario RFID desarrollada para **Newwearcorp**, diseñada para la pistola Zebra TC22 con escáner snap-on RFD4030.

---

## Dispositivos

| Componente | Modelo |
|---|---|
| PDT | Zebra TC22 — Android 14 |
| Escáner RFID | RFD4030-G00B700-US (Bluetooth) |
| Escáner Barcode | Integrado TC22 (DataWedge) |
| SDK RFID | Zebra RFID SDK v2.0.5.238 |

---

## Funcionalidades

- **Escaneo RFID** — via gatillo físico del RFD4030 o botón táctil en pantalla
- **Modo EPC** — muestra el código EPC crudo de cada etiqueta
- **Modo EAN** — decodifica etiquetas SGTIN-96 y extrae EAN-13 / GTIN-14
- **Estadísticas en tiempo real** — únicos, total de lecturas, velocidad (tags/seg)
- **Filtro de búsqueda** — busca por EPC, EAN-13 o GTIN-14 en tiempo real
- **Exportar CSV a PDT** — guarda el archivo en la carpeta que elijas
- **Exportar CSV a red** — sube el CSV directo a `\\10.150.1.24\refId` via SMB
- **Beep reducido** — volumen del RFD4030 en modo silencioso
- **Reinicio de app** — botón ↺ para reconectar el lector sin forzar detención manual

---

## Stack tecnológico

```
Kotlin
Zebra RFID SDK 2.0.5.238 (AAR locales en app/libs/)
Room DB + Hilt DI + Coroutines
JCIFS-NG 2.1.9 (exportación SMB)
ViewBinding
GitHub Actions (CI/CD)
```

---

## Estructura del proyecto

```
app/src/main/java/com/zebra/rfidscanner/
├── RfidApp.kt                    ← Application (Hilt)
├── data/
│   ├── TagEntry.kt               ← Entidad Room
│   ├── TagDao.kt                 ← Queries DB
│   ├── RfidDatabase.kt           ← Room DB
│   └── RfidRepository.kt         ← Stats: tagCount, totalReads, readRate
├── di/
│   └── AppModule.kt              ← Hilt modules
├── rfid/
│   └── RfidManager.kt            ← Conexión BT, inventario, gatillo físico
├── ui/
│   ├── ScanActivity.kt           ← UI principal, exportación, reinicio
│   ├── ScanViewModel.kt          ← Estado, EAN decode
│   └── EpcAdapter.kt             ← RecyclerView: EpcRow / EanRow
└── utils/
    ├── CsvExporter.kt            ← buildEpcCsv, buildEanCsv, saveToDownloads
    ├── SgtinDecoder.kt           ← SGTIN-96 → GTIN-14 → EAN-13
    └── SmbExporter.kt            ← Subida a carpeta de red Windows (SMB)
```

---

## AAR requeridos en `app/libs/`

Todos deben estar presentes para que compile:

```
API3_ASCII-release-2.0.5.238.aar
API3_CMN-release-2.0.5.238.aar
API3_INTERFACE-release-2.0.5.238.aar
API3_LLRP-release-2.0.5.238.aar
API3_NGE-protocolrelease-2.0.5.238.aar
API3_NGE-Transportrelease-2.0.5.238.aar
API3_NGEUSB-Transportrelease-2.0.5.238.aar
API3_READER-release-2.0.5.238.aar
API3_TRANSPORT-release-2.0.5.238.aar
API3_ZIOTC-release-2.0.5.238.aar
API3_ZIOTCTRANSPORT-release-2.0.5.238.aar
BarcodeScannerLibrary.aar         ← requerido para transporte Bluetooth
rfidhostlib.aar                   ← requerido para transporte Bluetooth
rfidseriallib.aar                 ← requerido para transporte Bluetooth
```

---

## Configuración de red SMB

La app guarda la configuración en SharedPreferences. Primera vez que exportas a red te pedirá:

| Campo | Valor |
|---|---|
| IP | `10.150.1.24` |
| Carpeta | `refId` |
| Dominio | `newwearcorp` |
| Usuario | tu usuario de dominio |
| Contraseña | tu contraseña de dominio |

---

## Configuración DataWedge (escáner de barcode)

Para usar el escáner de código de barras en el filtro de búsqueda:

1. Abrir **DataWedge** en la TC22
2. Entrar al perfil de la app o crear uno nuevo
3. **Associated Apps** → agregar `com.zebra.rfidscanner`
4. **Barcode Input** → Habilitado ✓
5. **Keystroke Output** → Deshabilitado ✗
6. **Intent Output** → Habilitado ✓
   - Intent action: `com.zebra.rfidscanner.SCAN`
   - Intent category: `android.intent.category.DEFAULT`
   - Intent delivery: `Start Activity`

> Si no se configura DataWedge, el escáner de barras funciona igual via Keystroke pero puede tener retraso al iniciar la app.

---

## Build — GitHub Actions

El workflow `.github/workflows/build.yml` compila automáticamente en cada push y genera el APK como artifact descargable.

```bash
# Para compilar localmente
./gradlew :app:assembleDebug
```

---

## Notas técnicas

### Conexión RFID
El `RfidManager` intenta conectar en este orden: `BLUETOOTH → SERVICE_SERIAL → SERVICE_USB → ALL`. Usa reconexión automática en caso de desconexión.

### SGTIN-96
Solo las etiquetas con header `0x30` son decodificables. Las etiquetas con header `0xE2` son genéricas sin producto codificado — muestran el EPC crudo.

### Sesión RFID
Configurado en `SESSION_S1` para que las etiquetas vuelvan automáticamente al estado A y puedan ser releídas en el siguiente disparo sin necesidad de reiniciar el inventario.

### Exportación SMB
Usa `JCIFS-NG 2.1.9` con SMB2 habilitado. Requiere que la PDT esté en el mismo segmento de red que el servidor Windows (`\\10.150.1.24\refId`).
