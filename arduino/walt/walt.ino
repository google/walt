/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define VERSION                 "6"

// Commands
// Digits 1 to 9 reserved for clock sync
#define CMD_PING_DELAYED        'D' // Ping/Pong with a delay
#define CMD_RESET               'F' // Reset all vars
#define CMD_SYNC_SEND           'I' // Send some digits for clock sync
#define CMD_PING                'P' // Ping/Pong with a single byte
#define CMD_VERSION             'V' // Determine which version is running
#define CMD_SYNC_READOUT        'R' // Read out sync times
#define CMD_GSHOCK              'G' // Send last shock time and watch for another shock.
#define CMD_TIME_NOW            'T' // Current time
#define CMD_SYNC_ZERO           'Z' // Initial zero

#define CMD_AUTO_SCREEN_ON      'C'
#define CMD_AUTO_SCREEN_OFF     'c'
#define CMD_SEND_LAST_SCREEN    'E'
#define CMD_BRIGHTNESS_CURVE    'U'

#define CMD_AUTO_LASER_ON       'L'
#define CMD_AUTO_LASER_OFF      'l'
#define CMD_SEND_LAST_LASER     'J'

#define CMD_AUDIO               'A'
#define CMD_BEEP                'B'
#define CMD_BEEP_STOP           'S'

#define CMD_SAMPLE_ALL          'Q'

#define CMD_MIDI                'M'
#define CMD_NOTE                'N'

#define CMD_ACCELEROMETER_CURVE 'O'

#define NOTE_DELAY 10000 // 10 ms

// Message types for MIDI encapsulation
#define MIDI_MODE_TYPE 4  // Program Change
#define MIDI_COMMAND_TYPE 5  // Channel Pressure

#define MIDI_SYSEX_BEGIN '\xF0'
#define MIDI_SYSEX_END '\xF7'

// LEDs
#define LED_PIN_INT 13 // Built-in LED
#define DEBUG_LED1 11  // On r0.7 PCB: D4 - Red
#define DEBUG_LED2 12  // On r0.7 PCB: D3 - Green

// WALT sensors
#define PD_LASER_PIN 14
#define PD_SCREEN_PIN 20  // Same as A6
#define G_PIN 15          // Same as A1
#define GZ_PIN 16         // Same as A2
#define AUDIO_PIN 22      // Same as A8
#define MIC_PIN 23        // Same as A9

// Threshold and hysteresis for screen on/off reading
#define SCREEN_THRESH_HIGH  110
#define SCREEN_THRESH_LOW  90

elapsedMicros time_us;

boolean led_state;
char tmp_str[256];

boolean serial_over_midi;
String send_buffer;

struct trigger {
  long t;  // time of latest occurrence in microseconds
  int value; // value at latest occurrence
  int count;  // occurrences since last readout
  boolean probe; // whether currently probing
  boolean autosend; // whether sending over serial each time
  char tag;
};

#define TRIGGER_COUNT 5
struct trigger laser, screen, sound, midi, gshock, copy_trigger;
struct trigger * triggers[TRIGGER_COUNT] = {&laser, &screen, &sound, &midi, &gshock};

#define CLOCK_SYNC_N 9
struct clock_sync {
  boolean is_synced;
  int last_sent;
  unsigned long sync_times[CLOCK_SYNC_N];
};

struct clock_sync clock;

// Interrupt handler for laser photodiode
void irq_laser(void) {
  laser.t = time_us;
  // May need to remove the 'not' if not using internal pullup resistor
  laser.value = !digitalRead(PD_LASER_PIN);
  laser.count++;

  digitalWrite(DEBUG_LED2, laser.value);
  // led_state = !led_state;
}

void send(char c) { send_buffer += c; }
void send(String s) { send_buffer += s; }

void send(long l) {
  char s[32];
  sprintf(s, "%ld", l);
  send(s);
}

void send(unsigned long l) {
  char s[32];
  sprintf(s, "%lu", l);
  send(s);
}

void send(short i) { send((long)i); }
void send(int i) { send((long)i); }
void send(unsigned short i) { send ((unsigned long)i); }
void send(unsigned int i) { send ((unsigned int)i); }

void send_now() {
  if (serial_over_midi) {
    usbMIDI.sendSysEx(send_buffer.length(), (const uint8_t *)send_buffer.c_str());
    usbMIDI.send_now();
    send_buffer = MIDI_SYSEX_BEGIN;
  } else {
    Serial.write(send_buffer.c_str(), send_buffer.length());
    Serial.send_now();
    send_buffer = String();
  }
}

void send_line() {
  if (!serial_over_midi) {
    send('\n');
  } else {
    send(MIDI_SYSEX_END);
  }
  send_now();
}

void send_trigger(struct trigger t) {
  char s[256];
  sprintf(s, "G %c %ld %d %d", t.tag, t.t, t.value, t.count);
  send(s);
  send_line();
}

// flips case for a give char. Unchanged if not in [A-Za-z].
char flip_case(char c) {
  if (c >= 'A' && c <= 'Z') {
    return c + 32;
  }
  if (c >= 'a' && c <= 'z') {
    return c - 32;
  }
  return c;
}

// Print the same char as the cmd but with flipped case
void send_ack(char cmd) {
  send(flip_case(cmd));
  send_line();
}

void init_clock() {
  memset(&clock, 0, sizeof(struct clock_sync));
  clock.last_sent = -1;
}

void init_vars() {
  noInterrupts();
  init_clock();

  for (int i = 0; i < TRIGGER_COUNT; i++) {
    memset(triggers[i], 0, sizeof(struct trigger));
  }

  laser.tag = 'L';
  screen.tag = 'S';
  gshock.tag = 'G';
  sound.tag = 'A';  // for Audio
  midi.tag = 'M';

  interrupts();
}

void setup() {
    // LEDs
  pinMode(DEBUG_LED1, OUTPUT);
  pinMode(DEBUG_LED2, OUTPUT);
  pinMode(LED_PIN_INT, OUTPUT);

  // Sensors
  pinMode(PD_SCREEN_PIN, INPUT);
  pinMode(G_PIN, INPUT);
  pinMode(GZ_PIN, INPUT);
  pinMode(PD_LASER_PIN, INPUT_PULLUP);
  attachInterrupt(PD_LASER_PIN, irq_laser, CHANGE);

  Serial.begin(115200);
  serial_over_midi = false;
  init_vars();

  led_state = HIGH;  // Turn on all LEDs on startup
  digitalWrite(LED_PIN_INT, led_state);
  digitalWrite(DEBUG_LED1, HIGH);
  digitalWrite(DEBUG_LED2, HIGH);
}


void run_brightness_curve() {
  int i;
  long t;
  short v;
  digitalWrite(DEBUG_LED1, HIGH);
  for (i = 0; i < 1000; i++) {
    v = analogRead(PD_SCREEN_PIN);
    t = time_us;
    send(t);
    send(' ');
    send(v);
    send_line();
    delayMicroseconds(450);
  }
  digitalWrite(DEBUG_LED1, LOW);
  send("end");
  send_line();
}

void run_accelerometer_curve() {
  int i;
  long t;
  int v;
  digitalWrite(DEBUG_LED1, HIGH);
  for (i = 0; i < 4000; i++) {
    v = analogRead(GZ_PIN);
    t = time_us;
    send(t);
    send(' ');
    send(v);
    send_line();
    delayMicroseconds(450);
  }
  digitalWrite(DEBUG_LED1, LOW);
  send("end");
  send_line();
}

void process_command(char cmd) {
  int i;
  if (cmd == CMD_SYNC_ZERO) {
    noInterrupts();
    time_us = 0;
    init_clock();
    clock.is_synced = true;
    interrupts();
    led_state = LOW;
    digitalWrite(DEBUG_LED1, LOW);
    digitalWrite(DEBUG_LED2, LOW);
    send_ack(CMD_SYNC_ZERO);
  } else if (cmd == CMD_TIME_NOW) {
    send("t ");
    send(time_us);
    send_line();
  } else if (cmd == CMD_PING) {
    send_ack(CMD_PING);
  } else if (cmd == CMD_PING_DELAYED) {
    delay(10);
    send_ack(CMD_PING_DELAYED);
  } else if (cmd >= '1' && cmd <= '9') {
    clock.sync_times[cmd - '1'] = time_us;
    clock.last_sent = -1;
  } else if (cmd == CMD_SYNC_READOUT) {
    clock.last_sent++;
    int t = 0;
    if (clock.last_sent < CLOCK_SYNC_N) {
      t = clock.sync_times[clock.last_sent];
    }
    send(clock.last_sent + 1);
    send(':');
    send(t);
    send_line();
  } else if (cmd == CMD_SYNC_SEND) {
    clock.last_sent = -1;
    // Send CLOCK_SYNC_N times
    for (i = 0; i < CLOCK_SYNC_N; ++i) {
      delayMicroseconds(737); // TODO: change to some congifurable random
      char c = '1' + i;
      clock.sync_times[i] = time_us;
      send(c);
      send_line();
    }
  } else if (cmd == CMD_RESET) {
    init_vars();
    send_ack(CMD_RESET);
  } else if (cmd == CMD_VERSION) {
    send(flip_case(cmd));
    send(' ');
    send(VERSION);
    send_line();
  } else if (cmd == CMD_GSHOCK) {
    send(gshock.t);  // TODO: Serialize trigger
    send_line();
    gshock.t = 0;
    gshock.count = 0;
    gshock.probe = true;
  } else if (cmd == CMD_AUDIO) {
    sound.t = 0;
    sound.count = 0;
    sound.probe = true;
    sound.autosend = true;
    send_ack(CMD_AUDIO);
  } else if (cmd == CMD_BEEP) {
    long beep_time = time_us;
    tone(MIC_PIN, 5000 /* Hz */);
    send(flip_case(cmd));
    send(' ');
    send(beep_time);
    send_line();
  } else if (cmd == CMD_BEEP_STOP) {
    noTone(MIC_PIN);
    send_ack(CMD_BEEP_STOP);
  } else if (cmd == CMD_MIDI) {
    midi.t = 0;
    midi.count = 0;
    midi.probe = true;
    midi.autosend = true;
    send_ack(CMD_MIDI);
  } else if (cmd == CMD_NOTE) {
    unsigned long note_time = time_us + NOTE_DELAY;
    send(flip_case(cmd));
    send(' ');
    send(note_time);
    send_line();
    while (time_us < note_time);
    usbMIDI.sendNoteOn(60, 99, 1);
    usbMIDI.send_now();
  } else if (cmd == CMD_AUTO_SCREEN_ON) {
    screen.value = analogRead(PD_SCREEN_PIN) > SCREEN_THRESH_HIGH;
    screen.autosend = true;
    screen.probe = true;
    send_ack(CMD_AUTO_SCREEN_ON);
  } else if (cmd == CMD_AUTO_SCREEN_OFF) {
    screen.autosend = false;
    screen.probe = false;
    send_ack(CMD_AUTO_SCREEN_OFF);
  } else if (cmd == CMD_SEND_LAST_SCREEN) {
    send_trigger(screen);
    screen.count = 0;
  } else if (cmd == CMD_AUTO_LASER_ON) {
    laser.autosend = true;
    laser.count = 0;
    send_ack(CMD_AUTO_LASER_ON);
  } else if (cmd == CMD_AUTO_LASER_OFF) {
    laser.autosend = false;
    send_ack(CMD_AUTO_LASER_OFF);
  } else if (cmd == CMD_SEND_LAST_LASER) {
    send_trigger(laser);
    laser.count = 0;
  } else if (cmd == CMD_BRIGHTNESS_CURVE) {
    send_ack(CMD_BRIGHTNESS_CURVE);
    // This blocks all other execution for about 1 second
    run_brightness_curve();
  } else if (cmd == CMD_ACCELEROMETER_CURVE) {
    send_ack(CMD_ACCELEROMETER_CURVE);
    // This blocks all other execution for about 2 seconds
    run_accelerometer_curve();
  } else if (cmd == CMD_SAMPLE_ALL) {
    send(flip_case(cmd));
    send(" G:");
    send(analogRead(G_PIN));
    send(" PD_screen:");
    send(analogRead(PD_SCREEN_PIN));
    send(" PD_laser:");
    send(analogRead(PD_LASER_PIN));
    send_line();
  } else {
    send("Unknown command: ");
    send(cmd);
    send_line();
  }
}

void loop() {
  digitalWrite(LED_PIN_INT, led_state);

  // Probe the accelerometer
  if (gshock.probe) {
    int v = analogRead(G_PIN);
    if (v > 900) {
      gshock.t = time_us;
      gshock.count++;
      gshock.probe = false;
      led_state = !led_state;
    }
  }

  // Probe audio
  if (sound.probe) {
    int v = analogRead(AUDIO_PIN);
    if (v > 20) {
      sound.t = time_us;
      sound.count++;
      sound.probe = false;
      led_state = !led_state;
    }
  }

  // Probe MIDI
  boolean has_midi = usbMIDI.read(1);
  if(has_midi && midi.probe && usbMIDI.getType() == 0) {  // Type 1: note on
    midi.t = time_us;
    midi.count++;
    midi.probe = false;
    led_state = !led_state;
  }

  // Probe screen
  if (screen.probe) {
    int v = analogRead(PD_SCREEN_PIN);
    if ((screen.value == LOW && v > SCREEN_THRESH_HIGH) || (screen.value != LOW && v < SCREEN_THRESH_LOW)) {
      screen.t = time_us;
      screen.count++;
      led_state = !led_state;
      screen.value = !screen.value;
    }
  }

  // Send out any triggers with autosend and pending data
  for (int i = 0; i < TRIGGER_COUNT; i++) {
    boolean should_send = false;

    noInterrupts();
    if (triggers[i]->autosend && triggers[i]->count > 0) {
      should_send = true;
      copy_trigger = *(triggers[i]);
      triggers[i]->count = 0;
    }
    interrupts();

    if (should_send) {
      send_trigger(copy_trigger);
    }
  }

  // Check if we got incoming commands from the host
  if (has_midi) {
    if (usbMIDI.getType() == MIDI_MODE_TYPE) {
      short program = usbMIDI.getData1();
      serial_over_midi = (program == 1);
      send_buffer = (serial_over_midi ? MIDI_SYSEX_BEGIN : String());
    } else if (usbMIDI.getType() == MIDI_COMMAND_TYPE) {
      char cmd = usbMIDI.getData1();
      process_command(cmd);
    }
  }
  if (Serial.available()) {
    char cmd = Serial.read();
    process_command(cmd);
  }
}

