/*
 * ═══════════════════════════════════════════════════════════════
 *  مکان‌بان — اسکچ برد گیرنده (makanban_receiver)
 * ═══════════════════════════════════════════════════════════════
 *
 *  وظیفهٔ برد:
 *   • دریافت خطوط داده از ماژول بلوتوث سری (SPP) که به پورت سریال برد وصل است
 *   • هنگام دریافت هر پیام: ال‌ای‌دی روی برد سه بار چشمک می‌زند
 *   • پاسخ تایید (ACK) با متن OK به ماژول بلوتوث (و در نتیجه به اپ)
 *   • آخرین موقعیت و تاریخ/ساعت شمسی دریافت‌شده در حافظهٔ برد به‌روز می‌ماند
 *     و روی پورت USB (مانیتور سری) برای اشکال‌زدایی چاپ می‌شود
 *
 *  قالب پیام‌های دریافتی از اپ:
 *   پیام موقعیت:  lat,lon,تاریخ شمسی,ساعت   مثال: 35.689200,51.389000,1405/08/10,14:30:25
 *   پیام زمان:    T,تاریخ شمسی,ساعت         مثال: T,1405/08/10,14:30:25
 *   پاسخ برد در هر دو حالت: OK
 *
 *  سیم‌کشی ماژول بلوتوث سری (SPP):
 *   VCC ماژول ← 5V یا 3V3 برد (طبق دیتاشیت ماژول)
 *   GND ماژول ← GND برد
 *   TX ماژول  ← پایهٔ BT_RX_PIN برد (در این اسکچ 18)
 *   RX ماژول  ← پایهٔ BT_TX_PIN برد (در این اسکچ 17)
 *   توجه: اگر ماژول سطح منطق 5V دارد و برد 3.3V، از مقسم ولتاژ روی RX ماژول استفاده کنید.
 *
 *  نکتهٔ راه‌اندازی:
 *   • در Arduino IDE نوع برد خود را از منوی Tools > Board انتخاب کنید.
 *   • پایه‌های ال‌ای‌دی و پورت سری ماژول را در بخش «تنظیمات» زیر با برد خود هماهنگ کنید.
 *   • کد پین جفت‌شدن ماژول بلوتوث معمولاً 1234 یا 0000 است.
 */

#include <Arduino.h>

// ───────────── تنظیمات (در صورت تفاوت برد خود را تغییر دهید) ─────────────

constexpr uint8_t  LED_PIN   = 15;      // پایهٔ ال‌ای‌دی روی برد (بعضی بردها 13 یا 2 دارند)
constexpr uint8_t  BT_RX_PIN = 18;      // RX برد ← به TX ماژول بلوتوث وصل شود
constexpr uint8_t  BT_TX_PIN = 17;      // TX برد ← به RX ماژول بلوتوث وصل شود
constexpr uint32_t BT_BAUD   = 9600;    // نرخ پیش‌فرض بیشتر ماژول‌های سری SPP
constexpr uint32_t USB_BAUD  = 115200;  // نرخ پورت USB برای مانیتور سری

HardwareSerial& btSerial = Serial1;

// ───────────── وضعیت به‌روز برد ─────────────

String latestLat   = "0.000000";
String latestLon   = "0.000000";
String latestJDate = "";                 // تاریخ شمسی گوشی، مثل 1405/08/10
String latestTime  = "";                 // ساعت گوشی، مثل 14:30:25
uint32_t updateCount = 0;

// ───────────── چشمک ال‌ای‌دی ─────────────

void blinkLed(uint8_t times, uint16_t onMs, uint16_t offMs) {
  for (uint8_t i = 0; i < times; i++) {
    digitalWrite(LED_PIN, HIGH);
    delay(onMs);
    digitalWrite(LED_PIN, LOW);
    if (i < times - 1) delay(offMs);
  }
}

// ───────────── راه‌اندازی ─────────────

void setup() {
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  Serial.begin(USB_BAUD);
  Serial.println();
  Serial.println("MakanBan receiver — ready");

  btSerial.begin(BT_BAUD, SERIAL_8N1, BT_RX_PIN, BT_TX_PIN);

  blinkLed(2, 80, 80);   // اعلام آماده‌باش
}

// ───────────── حلقهٔ اصلی ─────────────

void loop() {
  static String line;
  while (btSerial.available()) {
    char c = (char)btSerial.read();
    if (c == '\n') {
      handleLine(line);
      line = "";
    } else if (c != '\r' && line.length() < 200) {
      line += c;
    }
  }
}

// ───────────── پردازش هر خط دریافتی ─────────────

void handleLine(String& line) {
  line.trim();
  if (line.length() == 0) return;

  updateCount++;
  blinkLed(3, 60, 60);   // چشمک هنگام دریافت هر پیام

  // پیام فقط زمان:  T,1405/08/10,14:30:25
  if (line.startsWith("T,")) {
    int c1 = line.indexOf(',');
    int c2 = line.indexOf(',', c1 + 1);
    if (c1 > 0 && c2 > c1) {
      latestJDate = line.substring(c1 + 1, c2);
      latestTime  = line.substring(c2 + 1);
      Serial.printf("[TIME] #%lu  date=%s  time=%s\n",
                    (unsigned long)updateCount, latestJDate.c_str(), latestTime.c_str());
    }
    btSerial.println("OK");
    return;
  }

  // پیام موقعیت:  35.689200,51.389000,1405/08/10,14:30:25
  int c1 = line.indexOf(',');
  int c2 = line.indexOf(',', c1 + 1);
  int c3 = line.indexOf(',', c2 + 1);
  if (c1 > 0 && c2 > c1 && c3 > c2) {
    latestLat   = line.substring(0, c1);
    latestLon   = line.substring(c1 + 1, c2);
    latestJDate = line.substring(c2 + 1, c3);
    latestTime  = line.substring(c3 + 1);
    Serial.printf("[LOC ] #%lu  lat=%s  lon=%s  date=%s  time=%s\n",
                  (unsigned long)updateCount,
                  latestLat.c_str(), latestLon.c_str(),
                  latestJDate.c_str(), latestTime.c_str());
    btSerial.println("OK");
    return;
  }

  // پیام ناشناخته — باز هم تایید دریافت
  Serial.printf("[??? ] #%lu  msg=%s\n", (unsigned long)updateCount, line.c_str());
  btSerial.println("OK");
}
