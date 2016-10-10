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

#import "MIDIMessage.h"

const uint8_t kMIDINoChannel = -1;

MIDIMessageType MIDIMessageTypeFromStatus(MIDIByte status) {
  if (status < MIDIMessageSysEx) {
    return (status & 0xF0) >> 4;
  } else {
    return status;
  }
}

MIDIChannel MIDIChannelFromStatus(MIDIByte status) {
  if (status < MIDIMessageSysEx) {
    return (status & 0x0F) + 1;
  } else {
    return -1;
  }
}

NSData *MIDIMessageBody(NSData *message) {
  if (message.length == 0) {
    return nil;
  }
  
  const MIDIByte *bytes = (const MIDIByte *)message.bytes;
  
  // Slice off any header/trailer bytes.
  if (MIDIMessageTypeFromStatus(bytes[0]) == MIDIMessageSysEx) {
    NSCAssert(bytes[message.length - 1] == MIDIMessageSysExEnd, @"SysEx message without trailer.");
    return [message subdataWithRange:NSMakeRange(1, message.length - 2)];
  } else {
    return [message subdataWithRange:NSMakeRange(1, message.length - 1)];
  }
}

MIDIByte MIDIStatusByte(MIDIMessageType type, MIDIChannel channel) {
  if (type >= MIDIMessageSysEx) {
    return type;
  } else {
    return (type << 4) | (channel - 1);
  }
}

NSData *MIDIChannelMessageCreate(MIDIMessageType type, MIDIChannel channel, NSData *body) {
  NSMutableData *message =
      [[NSMutableData alloc] initWithCapacity:body.length + 2];  // +2 for status and SysEx trailer
  
  const MIDIByte status = MIDIStatusByte(type, channel);
  [message appendBytes:&status length:1];
  [message appendData:body];

  if (type == MIDIMessageSysEx) {
    const MIDIByte trailer = MIDIMessageSysEx;
    [message appendBytes:&trailer length:1];
  }
  
  return message;
}

NSData *MIDIMessageCreateSimple1(MIDIMessageType type, MIDIChannel channel, MIDIByte first) {
  NSCAssert(type != MIDIMessageSysEx, @"MIDIMessageCreateSimple1 cannot create SysEx messages.");

  NSMutableData *message = [[NSMutableData alloc] initWithCapacity:2];  // Status + Data
  
  const MIDIByte status = MIDIStatusByte(type, channel);
  [message appendBytes:&status length:1];
  [message appendBytes:&first length:1];
  
  return message;
}

NSData *MIDIMessageCreateSimple2(MIDIMessageType type,
                                 MIDIChannel channel,
                                 MIDIByte first,
                                 MIDIByte second) {
  NSCAssert(type != MIDIMessageSysEx, @"MIDIMessageCreateSimple2 cannot create SysEx messages.");
  
  NSMutableData *message = [[NSMutableData alloc] initWithCapacity:3];  // Status + Data + Data
  
  const MIDIByte status = MIDIStatusByte(type, channel);
  [message appendBytes:&status length:1];
  [message appendBytes:&first length:1];
  [message appendBytes:&second length:1];
  
  return message;
}
