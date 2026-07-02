# ECU_TESTER — Firmware (ESP-IDF v5.5.x)

ESP32-S3-WROOM-1 N16R8. Acquisition on core 1, web/WebSocket server on core 0.
Build/flash: see [`../docs/SETUP.md`](../docs/SETUP.md). Architecture:
[`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md). Wire format:
[`../docs/PROTOCOL.md`](../docs/PROTOCOL.md) (implemented by `main/include/protocol.h`).

`main.c` is a task-architecture skeleton; pinmap and acquisition detail land after the
`OPEN_DECISIONS.md` items are resolved. Do not retune `sdkconfig.defaults`, the partition
table, or pins without saying so (CLAUDE.md §6).
