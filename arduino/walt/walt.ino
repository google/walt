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

#define VERSION                 "2"

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

#define CMD_AUTO_LASER_ON       'L'
#define CMD_AUTO_LASER_OFF      'l'
#define CMD_SEND_LAST_LASER     'J'

#define CMD_AUDIO               'A'
#define CMD_BEEP                'B'

#define CMD_MIDI                'M'
#define CMD_NOTE                'N'

#define NOTE_DELAY 10000 // 10 ms


// On Teensy LC probably need to use the high current pins
// for most LEDs.
#define LED_PIN_GREEN 16
#define LED_PIN_RED 17
#define LED_PIN_YELLOW 5  // or 21

// Pins for the relay based touch probes, collide with LEDs above
#define TOUCH_PIN_A 16
#define TOUCH_PIN_B 17

// An pins below are the same on Teeny LC and 3.1
#define LED_PIN_INT 13
#define PD_LASER_PIN 14
#define PD_SCREEN_PIN 20 // Same as A6
#define G_PIN 15 // Same as A1
#define AUDIO_PIN 22 // Same as A8
#define MIC_PIN 23 // Same as A9

// Threshold and hysteresis for screen on/off reading
#define SCREEN_THRESH_HIGH  110
#define SCREEN_THRESH_LOW  90

elapsedMicros time_us;



boolean led_state;
char tmp_str[256];

struct trigger {
  long t;  // time of latest occurrence in microseconds
  int value; // value at latest occurrence
  int count;  // occurrences since last readout
  boolean probe; // whether currently probing
  boolean autosend; // whether sending over serial each time
  char tag;
};

#define TRIGGER_COUNT 5
struct trigger laser, screen, sound, midi, gshock, zero_trigger, copy_trigger;
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
  // digitalWrite(LED_PIN_INT, laser.value );
  led_state = !led_state;
}

void send_trigger(struct trigger t) {
  char s[256];
  sprintf(s, "G %c %ld %d %d", t.tag, t.t, t.value, t.count);
  Serial.println(s);
  Serial.send_now();
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
  Serial.println(flip_case(cmd));
  Serial.send_now();
}

void init_clock() {
  memset(&clock, 0, sizeof(struct clock_sync));
  clock.last_sent = -1;
}

void init_vars() {
  noInterrupts();
  init_clock();

  memset(&zero_trigger, 0, sizeof(struct trigger));
  laser = zero_trigger;

  laser.tag = 'L';
  screen.tag = 'S';
  gshock.tag = 'G';
  sound.tag = 'A';  // for Audio
  midi.tag = 'M';

  led_state = false;
  interrupts();
}

void setup() {
  pinMode(LED_PIN_RED, OUTPUT);
  pinMode(LED_PIN_GREEN, OUTPUT);
  pinMode(LED_PIN_YELLOW, OUTPUT);
  pinMode(LED_PIN_INT, OUTPUT);

  pinMode(TOUCH_PIN_A, OUTPUT);
  pinMode(TOUCH_PIN_B, OUTPUT);

  pinMode(PD_LASER_PIN, INPUT_PULLUP);
  pinMode(PD_SCREEN_PIN, INPUT);
  pinMode(G_PIN, INPUT);

  attachInterrupt(PD_LASER_PIN, irq_laser, CHANGE);

  // Turn on the red LED, will be turned off once time is synced
  led_state = HIGH;
  digitalWrite(LED_PIN_INT, led_state);
  // digitalWrite(LED_PIN_RED, HIGH);
  Serial.begin(115200);

  init_vars();
}


void process_command(char cmd) {
  int i;
  if (cmd == CMD_SYNC_ZERO) {
      noInterrupts();
      time_us = 0;
      init_clock();
      clock.is_synced = true;
      interrupts();
      send_ack(CMD_SYNC_ZERO);
    } else if (cmd == CMD_TIME_NOW) {
      Serial.print("t ");
      Serial.println(time_us);
      Serial.send_now();
    } else if (cmd == CMD_PING) {
      send_ack(CMD_PING);
    } else if (cmd == CMD_PING_DELAYED) {
      delay(10);
      send_ack(CMD_PING_DELAYED);
    } else if (cmd >= '1' && cmd <= '9') {
      clock.sync_times[cmd - '1'] = time_us;
    } else if (cmd == CMD_SYNC_READOUT) {
      clock.last_sent++;
      int t = 0;
      if (clock.last_sent < CLOCK_SYNC_N) {
        t = clock.sync_times[clock.last_sent];
      }
      Serial.print(clock.last_sent + 1);
      Serial.print(":");
      Serial.println(t);
      Serial.send_now();
    } else if (cmd == CMD_SYNC_SEND) {
      clock.last_sent = -1;
      // Send CLOCK_SYNC_N times
      for (i = 0; i < CLOCK_SYNC_N; ++i) {
        delayMicroseconds(737); // TODO: change to some congifurable random
        char c = '1' + i;
        clock.sync_times[i] = time_us;
        Serial.print(c);
        Serial.send_now();
      }
      // TODO: This newline is useful for debugging, think if it's ok with the rest.
      Serial.println();
      Serial.send_now();
    } else if (cmd == CMD_RESET) {
      init_vars();
      send_ack(CMD_RESET);
    } else if (cmd == CMD_VERSION) {
      Serial.print(flip_case(cmd));
      Serial.print(" ");
      Serial.println(VERSION);
      Serial.send_now();
    } else if (cmd == CMD_GSHOCK) {
      Serial.println(gshock.t); // TODO: Serialize trigger
      Serial.send_now();
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
      tone(MIC_PIN, 5000 /* Hz */, 200 /* ms */);
      Serial.print(flip_case(cmd));
      Serial.print(" ");
      Serial.println(beep_time);
      Serial.send_now();
    } else if (cmd == CMD_MIDI) {
      midi.t = 0;
      midi.count = 0;
      midi.probe = true;
      midi.autosend = true;
      send_ack(CMD_MIDI);
    } else if (cmd == CMD_NOTE) {
      long note_time = time_us + NOTE_DELAY;
      Serial.print(flip_case(cmd));
      Serial.print(" ");
      Serial.println(note_time);
      Serial.send_now();
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
      send_ack(CMD_AUTO_LASER_ON);
    } else if (cmd == CMD_AUTO_LASER_OFF) {
      laser.autosend = false;
      send_ack(CMD_AUTO_LASER_OFF);
    } else if (cmd == CMD_SEND_LAST_LASER) {
      send_trigger(laser);
      laser.count = 0;
    } else {
      Serial.print("Unknown command:");
      Serial.println(cmd);
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
  if(midi.probe) {
    if(usbMIDI.read()) {
      midi.t = time_us;
      midi.count++;
      midi.probe = false;
      led_state = !led_state;
    }
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
  if (Serial.available()) {
    char cmd = Serial.read();
    process_command(cmd);
  }
}
