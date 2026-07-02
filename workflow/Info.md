The dashboard will be connected to an ESP32-S3-WROOM-1 *N16R8*  controller that acquires approx. 50 sensors data and ECU signals in real time.

This is a local web application

The dashboard must be developed using a single or multiple Monitor and optimized for embedded environments running on IDF version 5.5.x

The connection will be AP user name ECU-TESTER and the password 00000000 , the IP 10.10.10.10 display and must boot automatically into the dashboard.



---
Mandatory Technical Requirements
Software Stack:

* C/C++
* ESP32-S3 

The application must:

* Run full screen (Landscape)
* Run without a smart TV environment (and PC desktop) through out Internet explorer. 
*  Launch automatically on boot.
* Output to any device.
* Operate as a professional frontend local website.
---

System Architecture Requirements

The application must be designed using a modular component architecture.

Examples:

* Gauge.qml
* VoltageMeter.qml
* Indicator.qml
* WaveformView.qml
* InjectorAnimation.qml
* CoilIndicator.qml

Requirements:

* Components must be reusable.
* New gauges must be easily added.
* New indicators must be easily added.
* Layout changes should require minimal code modifications.
* Future expansion should not require redesigning the UI.

---

Communication Protocol

Mandatory
- The system must use light payload like (json or similar)

Avoid:
* Higher CPU usage
* Slower parsing
* Fragile communication
* Poor scalability

Required approach:
* Packed binary data structure
* Fixed packet format
* CRC validation
* Packet synchronization
* Error detection

The protocol must be fully documented.
---

ESP32-S3 Firmware

Firmware responsibilities:
Analog Inputs
* MAF
* MAP
* IAT
* ECU Voltage
* Sensor Voltage

Digital Signals
* 8 Ignition Coil Channels
* 8 Injector Channels
* Battery
* Switch
* Start Switch Button
* ETC
* Fan 1
* Fan 2
* Fuel Pump
* IMMO+
* IMMO-
* MRC+
* MRC-
* IAC Stepper Signals

Signal Processing
* CKP acquisition
* CMP1 acquisition
* CMP2 acquisition

Requirements
* Timer interrupt architecture
* Waveform buffering
* Extensible code structure
* Well-documented source code

Preferred implementation:
* High speed
* 10 kHz acquisition rate minimum.

However, I am open to suggestions that result in the betterment of the final prototype.

---

Dashboard Features

Analog Gauges

Animated circular gauges:

* MAF
* MAP
* IAT

Voltage Displays
Digital voltage meters:
* ECU Voltage (0–25V)
* Sensor Voltage (0–5V)

Ignition Coils
* 8 coil indicators
* Positive state visualization
* Negative state visualization
* Firing animation (Spark)

Injectors (regular)
* 8 injector indicators
* Animated spray effect

Injectors (GDI)

* 8 injector indicators
* Animated spray effect

Digital Status Indicators
* Battery
* Switch
* Start
* ETC
* Fan 1
* Fan 2
* Fuel Pump
* IMMO+
* IMMO-
* MRC+
* MRC-
* IAC signals

Waveform Oscilloscope:
Display real-time scrolling waveforms for:
* CKP
* CMP1
* CMP2

The waveform display is a mandatory feature.
Simple blinking LEDs are not acceptable.
The oscilloscope should allow visualization of:

* Missing tooth patterns
* Cam synchronization
* Timing relationships
* Signal integrity

And I am open to suggestions here too!

---

## Real Time Animation
A complete animation is required.
The dashboard must operate without hardware connected.
Simulation mode should generate realistic values for:

* Gauges
* Voltage readings
* Current reading
* Coil activity (Spark)
* Injector activity (Spray)
* CKP waveform (PWM)
* CMP waveform (PWM)
* Status indicators 

Purpose:
* Demonstrations
* Development
* Testing
* Troubleshooting

---

Functions:

Analog Controls

Manual sliders for:

* MAF
* MAP
* IAT
* ECU Voltage
* ECU Current

Digital Controls

Manual toggles for:

* Coils
* Injectors
* Battery
* Switch
* Fuel Pump
* IMMO
* MRC
* IAC
* Fan outputs

### Waveform Controls

Ability to:

* Real time RPM
* Real time CKP
* Real time CMP

This feature will be heavily used during development and validation.

---

## IDF Deployment

Developer must provide:

* systemd service
* automatic startup
* watchdog-safe startup sequence

Performance Optimization

Target hardware:

* ESP32-S3 WROOM N16 R8

The dashboard must maintain smooth operation and responsiveness on this platform.

---

Deliverables

Source Code

Complete source code including:
* IDF v5.5.x firmware

Executables

* Ready-to-run deployment package

### Deployment Scripts

* Installation scripts
* Startup scripts
* systemd configuration

### Documentation

Comprehensive documentation including:

1. System Setup Guide
2. Communication Protocol Specification
3. Dashboard Architecture Guide
4. Adding New Sensors and Components Guide
5. Simulation Mode Guide


---

## Required Experience

Applicants should demonstrate experience with:

* Real-time serial communications
* Automotive or industrial systems
* GPU-accelerated embedded UI development

--------------------------------------------------------------------

## Local Web page

build a realistic, interactive HTML page using a real photo that is controlled directly by an ESP32-S3. The ESP32-S3 has plenty of processing power, ample flash memory, and usually PSRAM, making it a highly capable web server for this type of project.

However, achieving a response that is **fast and smooth** requires abandoning traditional HTTP request-response loops and adopting a more modern, persistent architecture.

Here is the exact technical blueprint to make it happen without lag.

### 1. The Communication Layer: WebSockets

If you want smooth, instant responsiveness (e.g., pressing a physical button connected to the ESP32-S3 and instantly seeing a change on the web page), standard HTTP GET/POST requests will be too slow due to header overhead and connection setup times.  

You must use **WebSockets**.

* Real-time serial communications
* Automotive or industrial systems
* GPU-accelerated embedded UI development

WebSockets create a persistent, full-duplex connection between the ESP32-S3 and the browser. Once the initial HTML page loads, the WebSocket connection stays open.  

- **ESP32-S3 to Browser:** When a sensor triggers or a state changes, the ESP32-S3 pushes a tiny, lightweight JSON payload (e.g., `{"valve_status": "open"}`) directly to the browser.
- **Browser to ESP32-S3:** When a user clicks a part of the photo on the screen, the browser instantly sends a command back (e.g., `{"command": "turn_on_motor"}`).

In **ESP-IDF**, you can achieve this using the `esp_http_server` component, which has native, highly efficient WebSocket support (`httpd_ws_frame_t`).

### 2. Handling the Real Photo (Asset Delivery)

Real photos (JPEGs or PNGs) are large files. If the ESP32-S3 has to re-send a 500KB photo every time the user refreshes the page or triggers an action, the interface will feel sluggish and the Wi-Fi stack will choke.

- **Storage:** Store the HTML, CSS, JavaScript, and the image files in the ESP32-S3's flash memory using the **LittleFS** or **SPIFFS** file system. LittleFS is generally faster and more reliable for modern ESP-IDF projects.  
- **Caching (Crucial):** When the browser requests the photo for the first time, your ESP32-S3 must serve it with an HTTP `Cache-Control` header (e.g., `Cache-Control: public, max-age=31536000`). This forces the browser to save the image locally. On all future interactions or page reloads, the photo will load instantly from the device's local memory, leaving the ESP32-S3 free to handle only the high-speed WebSocket control data.
- **Flash Optimization:** To maximize read speeds from the file system, ensure your flash mode is set to **QIO** (Quad I/O) and your flash frequency is cranked up (e.g., 80MHz) in your `menuconfig`.

### 3. Front end Architecture: The Interactive Photo

To make a static photograph interactive based on ESP32-S3 commands, you will handle the logic entirely in the browser using JavaScript, CSS, and HTML5.

Here are the best ways to layer interactivity over a real photo:

- **SVG Overlays (Recommended):** Place an invisible SVG (Scalable Vector Graphic) exactly over the top of your photograph. You can draw interactive polygons over specific parts of the image (like a screen, a button, or a pipe). When a WebSocket message arrives from the ESP32-S3, JavaScript changes the CSS properties (like `fill-opacity` or `filter: drop-shadow`) of those specific SVG shapes to make them "glow" or change color.
- **CSS Clip-Path / Z-Index:** Load a second version of the photo (e.g., a photo where an LED indicator is turned ON) and layer it perfectly underneath the main photo. Use JavaScript to manipulate the opacity of the top image or use CSS `clip-path` to reveal the glowing state beneath it dynamically.
- **HTML5 Canvas:** If the interactivity requires complex animations or particle effects over the photo, draw the photo onto an HTML5 `<canvas>` and use JavaScript to render animations on top of it based on the ESP32-S3's telemetry data.

### Summary of the Data Flow

1. **Boot:** ESP32-S3 mounts LittleFS, connects to Wi-Fi, and starts `esp_http_server`.
2. **Initial Load:** User opens the IP address. The ESP32-S3 serves the HTML, JS, and the heavy photo file (with strict caching headers).
3. **Connection:** The browser's JavaScript opens a WebSocket connection back to the ESP32-S3.
4. **Operation:** The user clicks an area of the photo. JavaScript catches the click, sends a 20-byte WebSocket message to the ESP32-S3. The ESP32-S3 processes it in milliseconds, updates its hardware state, and broadcasts a tiny JSON state-update back. The browser updates the UI instantly.