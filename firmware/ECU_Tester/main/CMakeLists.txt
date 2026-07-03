idf_component_register(
    SRCS "main.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_http_server esp_wifi nvs_flash esp_adc driver
             esp_netif esp_event esp_timer joltwallet__littlefs
)
# NOTE: joltwallet__littlefs is the managed LittleFS component (public header
# esp_littlefs.h). It is pulled by main/idf_component.yml into managed_components/
# on first configure. See docs/SETUP.md.

# Build a LittleFS image from the repo's web/ dir and flash it to the 'storage'
# partition alongside the app (FLASH_IN_PROJECT => included in `idf.py flash`).
# Path is relative to this file: main/ -> ../../../web (repo root). The helper
# builds the image with littlefs-python in a build-time venv (needs pip once).
littlefs_create_partition_image(storage ../../../web FLASH_IN_PROJECT)
