//for OV2640 - satellite camera
#pragma once

#include "esp_camera.h"
#define CAMERA_MODEL_AI_THINKER
#include "camera_pins.h"
#include <LittleFS.h>
#include <ArduinoJson.h>
#include <vector>
#include "base64.h"

extern void logDebug(String msg);

const int MAX_PHOTOS = 10;
const char* PHOTO_INDEX_FILE = "/photo_index.json";

struct PhotoRecord {
  int id;
  String timestamp;
  String filename;
};

int globalPhotoCounter = 0;
std::vector<PhotoRecord> photoRecords;

bool init_camera() {
  // Previously `camera_config_t config;` was declared with no initializer
  // and only ~20 of its fields were assigned below, leaving fb_location,
  // grab_mode and sccb_i2c_port as indeterminate stack garbage — whether
  // the framebuffer landed in PSRAM was down to chance. See
  // docs/HARDWARE_NOTES.md #1. Zero-initializing and setting fb_location
  // from a real psramFound() check fixes that, with a conservative
  // DRAM+SVGA fallback if no PSRAM is detected.
  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sscb_sda = SIOD_GPIO_NUM;
  config.pin_sscb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.fb_count = 1;
  config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;

  if (psramFound()) {
    config.frame_size = FRAMESIZE_XGA;
    config.jpeg_quality = 15;
    config.fb_location = CAMERA_FB_IN_PSRAM;
    logDebug("[CAM] PSRAM found - framebuffer in PSRAM, XGA q15");
  } else {
    config.frame_size = FRAMESIZE_SVGA;
    config.jpeg_quality = 20;
    config.fb_location = CAMERA_FB_IN_DRAM;
    LOG_WARN("[CAM] No PSRAM detected - framebuffer in DRAM, downgraded to SVGA q20");
  }

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("▲ Camera init failed with error 0x%x", err);
    Serial.flush();
    return false;
  }

  return true;
}

void initPhotoStorage() {
  logDebug("[PHOTO] Total space: " + String(LittleFS.totalBytes()));
  logDebug("[PHOTO] Used space: " + String(LittleFS.usedBytes()));

  if (LittleFS.exists(PHOTO_INDEX_FILE)) {
    logDebug("[PHOTO] Found existing index: " + String(PHOTO_INDEX_FILE));

    File indexFile = LittleFS.open(PHOTO_INDEX_FILE, "r");
    if (indexFile) {
      DynamicJsonDocument doc(2048);
      DeserializationError error = deserializeJson(doc, indexFile);
      indexFile.close();

      if (!error) {
        globalPhotoCounter = doc["counter"] | 0;
        JsonArray photos = doc["photos"].as<JsonArray>();

        for (JsonObject photo : photos) {
          PhotoRecord record;
          record.id = photo["id"];
          record.timestamp = photo["timestamp"].as<String>();
          record.filename = photo["filename"].as<String>();
          photoRecords.push_back(record);
        }

        logDebug("[PHOTO] Index loaded. Found " + String(photoRecords.size()) + " photos");
      } else {
        LOG_ERROR("[PHOTO] Failed to parse index JSON: " + String(error.c_str()));
        LOG_INFO("[PHOTO] Starting with empty index");
        globalPhotoCounter = 0;
      }
    } else {
      LOG_ERROR("[PHOTO] Cannot open index file");
    }
    File root = LittleFS.open("/");
      File file = root.openNextFile();
      while(file){
        String fileName = file.name();
        if(fileName.startsWith("photo_") && fileName.endsWith(".jpg")){
          bool found = false;
          for(const auto& record : photoRecords){
            if(record.filename == "/" + fileName || record.filename == fileName){
              found = true;
              break;
            }
          }
          if(!found){
            logDebug("[PHOTO] Cleanup: deleting orphan file " + fileName);
            String fullPath = "/" + fileName;
            file.close();
            LittleFS.remove(fullPath);
            root = LittleFS.open("/");
          }
        }
        file = root.openNextFile();
      }
  } else {
    logDebug("[PHOTO] No existing photo index, starting fresh");
    globalPhotoCounter = 0;
  }
}

void savePhotoIndex() {
  DynamicJsonDocument doc(3072);
  doc["counter"] = globalPhotoCounter;

  JsonArray photos = doc.createNestedArray("photos");
  for (const auto& record : photoRecords) {
    JsonObject photo = photos.createNestedObject();
    photo["id"] = record.id;
    photo["timestamp"] = record.timestamp;
    photo["filename"] = record.filename;
  }

  File indexFile = LittleFS.open(PHOTO_INDEX_FILE, "w");
  if (indexFile) {
    serializeJson(doc, indexFile);
    indexFile.close();
    logDebug("[PHOTO] Index saved");
  }
}

bool savePhoto(camera_fb_t* fb, const char* timestamp) {
  if (photoRecords.size() >= MAX_PHOTOS) {
    logDebug("[PHOTO] Max limit reached. Deleting: " + photoRecords[0].filename);
    LittleFS.remove(photoRecords[0].filename.c_str());
    photoRecords.erase(photoRecords.begin());
  }

  int nextId = globalPhotoCounter + 1;

  logDebug("[PHOTO] Saving photo #" + String(nextId));
  logDebug("[PHOTO] RAM free: " + String(ESP.getFreeHeap()) + " bytes");

  char filename[32];
  snprintf(filename, sizeof(filename), "/photo_%d.jpg", nextId);

  File photoFile = LittleFS.open(filename, "w");
  if (!photoFile) {
    LOG_ERROR("[PHOTO] Failed to open file for writing");

    logDebug("[PHOTO] Total space: " + String(LittleFS.totalBytes()));
    logDebug("[PHOTO] Used space: " + String(LittleFS.usedBytes()));

    return false;
  }

  size_t written = photoFile.write(fb->buf, fb->len);
  photoFile.close();

  if (written != fb->len) {
    LOG_ERROR("[PHOTO] Write failed: expected " + String(fb->len) + " bytes, wrote " + String(written));
    LittleFS.remove(filename);
    return false;
  }

  globalPhotoCounter = nextId;
  PhotoRecord record;
  record.id = globalPhotoCounter;
  record.timestamp = String(timestamp);
  record.filename = String(filename);
  photoRecords.push_back(record);

  savePhotoIndex();

   LOG_INFO("[PHOTO] Saved photo #" + String(globalPhotoCounter) + " (" + String(written) + " bytes)");
  return true;
}

enum PhotoCaptureError { PHOTO_ERR_NONE, PHOTO_ERR_CAPTURE, PHOTO_ERR_SAVE };

struct PhotoCaptureResult {
  bool ok = false;
  camera_fb_t* fb = nullptr;  // valid only if ok; caller MUST esp_camera_fb_return(fb) when done
  int id = 0;
  PhotoCaptureError error = PHOTO_ERR_NONE;
};

// Shared capture path for both /get_photo (server.h) and the BLE
// TAKE_PHOTO command (command_bus.h) — factored out so there is one
// implementation of "capture, with a bounded retry, then save" rather
// than two. Replaces the original unbounded `while (!fb) { fb =
// esp_camera_fb_get(); }` (which could hang loop() forever on a
// persistent capture failure) with a fixed retry count.
PhotoCaptureResult capturePhotoToStorage(const char* timestamp) {
  PhotoCaptureResult result;

  camera_fb_t* old_fb = esp_camera_fb_get();
  if (old_fb) {
    esp_camera_fb_return(old_fb);
    logDebug("[PHOTO] Initial frame discarded.");
  }

  camera_fb_t* fb = nullptr;
  const int CAPTURE_RETRIES = 3;
  for (int attempt = 0; attempt < CAPTURE_RETRIES && !fb; attempt++) {
    fb = esp_camera_fb_get();
  }
  if (!fb) {
    logDebug("[PHOTO] Capture failed after " + String(CAPTURE_RETRIES) + " attempts");
    result.error = PHOTO_ERR_CAPTURE;
    return result;
  }

  if (!savePhoto(fb, timestamp)) {
    esp_camera_fb_return(fb);
    result.error = PHOTO_ERR_SAVE;
    return result;
  }

  result.ok = true;
  result.fb = fb;
  result.id = globalPhotoCounter;
  return result;
}

String getPhotoListJson() {
  DynamicJsonDocument doc(512);
  JsonArray ids = doc.createNestedArray("ids");

  for (const auto& record : photoRecords) {
    ids.add(record.id);
  }

  String result;
  serializeJson(doc, result);
  return result;
}

PhotoRecord* getPhotoById(int id) {
  for (auto& record : photoRecords) {
    if (record.id == id) {
      return &record;
    }
  }
  return nullptr;
}

void get_photo(){
  
}
