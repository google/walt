/*
 * Copyright (C) 2016 The Android Open Source Project
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

#import <Foundation/Foundation.h>

/**
 * A MIDI channel number.
 *
 * Note that the first channel is '1'.
 */
typedef uint8_t MIDIChannel;
typedef uint8_t MIDIByte;

typedef NS_ENUM(MIDIByte, MIDIMessageType) {
  // Channel messages
  MIDIMessageNoteOff          = 0x08,
  MIDIMessageNoteOn           = 0x09,
  MIDIMessageKeyPressure      = 0x0A,
  MIDIMessageControlChange    = 0x0B,
  MIDIMessageProgramChange    = 0x0C,
  MIDIMessageChannelPressure  = 0x0D,
  MIDIMessagePitchBend        = 0x0E,
  
  // System messages
  MIDIMessageSysEx            = 0xF0,
  MIDIMessageQuarterFrame     = 0xF1,
  MIDIMessageSongPosition     = 0xF2,
  MIDIMessageSongSelect       = 0xF3,
  MIDIMessageTuneRequest      = 0xF6,
  MIDIMessageSysExEnd         = 0xF7,
  MIDIMessageTimingClock      = 0xF8,
  MIDIMessageStart            = 0xFA,
  MIDIMessageContinue         = 0xFB,
  MIDIMessageStop             = 0xFC,
  MIDIMessageActiveSensing    = 0xFE,
  MIDIMessageReset            = 0xFF,
};

extern const MIDIChannel kMIDINoChannel;

#pragma mark Message Parsing

/** Returns the MIDIMessageType for a given status byte. */
MIDIMessageType MIDIMessageTypeFromStatus(MIDIByte status);

/**
 * Returns the MIDIChannel for a given status byte, or kMIDINoChannel if the status byte does not
 * describe a channel message.
 */
MIDIChannel MIDIChannelFromStatus(MIDIByte status);

/**
 * Returns the body portion from a complete MIDI message (i.e., without leading or trailing data).
 */
NSData *MIDIMessageBody(NSData *message);

#pragma mark Message Building

/** Returns the MIDI status byte for a message type sent to a particular channel. */
MIDIByte MIDIStatusByte(MIDIMessageType type, MIDIChannel channel);

/** Creates a complete MIDI message packet for a given message type, channel, and its body. */
NSData *MIDIMessageCreate(MIDIMessageType type, MIDIChannel channel, NSData *body);

/** Creates a complete MIDI message packet for a simple message containing one data byte. */
NSData *MIDIMessageCreateSimple1(MIDIMessageType type, MIDIChannel channel, MIDIByte first);

/** Creates a complete MIDI message packet for a simple message containing two data bytes. */
NSData *MIDIMessageCreateSimple2(MIDIMessageType type,
                                 MIDIChannel channel,
                                 MIDIByte first,
                                 MIDIByte second);
