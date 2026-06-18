# OBDProxy

Android OBD-II Bluetooth proxy for DKE HUD — bridges vLinker MS ELM327 adapter to Mercedes UDS protocol over SPP.

## How It Works

```
OBD-II Car → vLinker MS (Bluetooth SPP) → OBDProxy (Android) → DKE HUD (Bluetooth SPP)
```

The proxy:
1. Connects to vLinker MS via Bluetooth SPP
2. Initializes ELM327 with ISO 15765-4 CAN 11/500 protocol
3. Polls standard OBD-II Mode 01 PIDs (RPM, Speed, Coolant, MAP, etc.)
4. Translates OBD-II data to Mercedes UDS DID responses on-the-fly
5. Serves a fake OBDLink MX SPP server for DKE HUD to connect

## Build

Requires: Python 3, Android SDK (build-tools 36+), JDK 17

```bash
python build.py
```

## Calibration

In-app commands to override HUD display values:

| Key | Parameter | Example |
|-----|-----------|---------|
| R=  | RPM       | R=8000  |
| S=  | Coolant °C| S=90    |
| B=  | Speed km/h| B=80    |
| T=  | Torque Nm | T=960   |
| C=  | Accel %   | C=55    |
| H=  | Boost PSI | H=1000  |
| I=  | IAT °C    | I=30    |
| E=  | Retard °  | E=10    |
| A=  | AFR       | A=1470  |

## Hardware

- vLinker MS 06330 (STN2120 v5.8.1)
- Tested with Chery Arrizo 8 2.0T (SQRF4J20)
- Protocol: ISO 15765-4 CAN 11-bit 500kbaud

## License

MIT
