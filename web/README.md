# ECU_TESTER — Web dashboard

Landscape/fullscreen local dashboard, served from the device's LittleFS `storage`
partition, driven over a binary WebSocket. Runs in a Chromium kiosk (OPEN_DECISIONS D8).
`protocol.js` must stay in lockstep with `../firmware/main/include/protocol.h`. Adding
gauges/indicators: [`../docs/ADDING_COMPONENTS.md`](../docs/ADDING_COMPONENTS.md).
