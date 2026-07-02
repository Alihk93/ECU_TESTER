idf_component_register(
    SRCS "main.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_http_server esp_wifi nvs_flash esp_adc driver
             esp_netif esp_event esp_timer joltwallet__littlefs
)
# NOTE: joltwallet__littlefs is the managed LittleFS component (public header
# esp_littlefs.h). It is pulled by main/idf_component.yml into managed_components/
# on first configure. See docs/SETUP.md.
