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

#import "MIDIClient.h"

#include <CoreMIDI/CoreMIDI.h>

#import "MIDIEndpoint.h"
#import "MIDIMessage.h"

NSString * const MIDIClientErrorDomain = @"MIDIClientErrorDomain";

@interface MIDIClient ()
@property (readwrite, nonatomic) MIDISource *source;
@property (readwrite, nonatomic) MIDIDestination *destination;
// Used by midiRead() for SysEx messages spanning multiple packets.
@property (readwrite, nonatomic) NSMutableData *sysExBuffer;

/** Returns whether the client's source or destination is attached to a particular device. */
- (BOOL)attachedToDevice:(MIDIDeviceRef)device;
@end

// Note: These functions (midiStateChanged and midiRead) are not called on the main thread!
static void midiStateChanged(const MIDINotification *message, void *context) {
  MIDIClient *client = (__bridge MIDIClient *)context;
  
  switch (message->messageID) {
    case kMIDIMsgObjectAdded: {
      const MIDIObjectAddRemoveNotification *notification =
          (const MIDIObjectAddRemoveNotification *)message;
      
      @autoreleasepool {
        if ((notification->childType & (kMIDIObjectType_Source|kMIDIObjectType_Destination)) != 0 &&
            [client.delegate respondsToSelector:@selector(MIDIClientEndpointAdded:)]) {
          [client.delegate MIDIClientEndpointAdded:client];
        }
      }
      break;
    }

    case kMIDIMsgObjectRemoved: {
      const MIDIObjectAddRemoveNotification *notification =
          (const MIDIObjectAddRemoveNotification *)message;

      @autoreleasepool {
        if ((notification->childType & (kMIDIObjectType_Source|kMIDIObjectType_Destination)) != 0 &&
            [client.delegate respondsToSelector:@selector(MIDIClientEndpointRemoved:)]) {
          [client.delegate MIDIClientEndpointRemoved:client];
        }
      }
      break;
    }

    case kMIDIMsgSetupChanged:
    case kMIDIMsgPropertyChanged:
    case kMIDIMsgSerialPortOwnerChanged:
    case kMIDIMsgThruConnectionsChanged: {
      @autoreleasepool {
        if ([client.delegate respondsToSelector:@selector(MIDIClientConfigurationChanged:)]) {
          [client.delegate MIDIClientConfigurationChanged:client];
        }
      }
      break;
    }

    case kMIDIMsgIOError: {
      const MIDIIOErrorNotification *notification = (const MIDIIOErrorNotification *)message;
      
      if ([client attachedToDevice:notification->driverDevice]) {
        @autoreleasepool {
          NSError *error = [NSError errorWithDomain:NSOSStatusErrorDomain
                                               code:notification->errorCode
                                           userInfo:nil];
          if ([client.delegate respondsToSelector:@selector(MIDIClient:receivedError:)]) {
            [client.delegate MIDIClient:client receivedError:error];
          }
        }
      }
      break;
    }
      
    default: {
      NSLog(@"Unhandled MIDI state change: %d", (int)message->messageID);
    }
  }
}

static void midiRead(const MIDIPacketList *packets, void *portContext, void *sourceContext) {
  MIDIClient *client = (__bridge MIDIClient *)portContext;
  
  // Read the data out of each packet and forward it to the client's delegate.
  // Each MIDIPacket will contain either some MIDI commands, or the start/continuation of a SysEx
  // command. The start of a command is detected with a byte greater than or equal to 0x80 (all data
  // must be 7-bit friendly). The end of a SysEx command is marked with 0x7F.
  
  // TODO(pquinn): Should something be done with the timestamp data?
  
  UInt32 packetCount = packets->numPackets;
  const MIDIPacket *packet = &packets->packet[0];
  @autoreleasepool {
    while (packetCount--) {
      if (packet->length == 0) {
        continue;
      }
      
      const Byte firstByte = packet->data[0];
      const Byte lastByte = packet->data[packet->length - 1];
      
      if (firstByte >= 0x80 && firstByte != MIDIMessageSysEx && firstByte != MIDIMessageSysExEnd) {
        // Packet describes non-SysEx MIDI messages.
        NSMutableData *data = nil;
        for (UInt16 i = 0; i < packet->length; ++i) {
          // Packets can contain multiple MIDI messages.
          if (packet->data[i] >= 0x80) {
            if (data.length > 0) {  // Tell the delegate about the last extracted command.
              [client.delegate MIDIClient:client receivedData:data];
            }
            data = [[NSMutableData alloc] init];
          }
          [data appendBytes:&packet->data[i] length:1];
        }
        
        if (data.length > 0) {
          [client.delegate MIDIClient:client receivedData:data];
        }
      }
      
      if (firstByte == MIDIMessageSysEx) {
        // The start of a SysEx message; collect data into sysExBuffer.
        client.sysExBuffer = [[NSMutableData alloc] initWithBytes:packet->data
                                                           length:packet->length];
      } else if (firstByte < 0x80 || firstByte == MIDIMessageSysExEnd) {
        // Continuation or end of a SysEx message.
        [client.sysExBuffer appendBytes:packet->data length:packet->length];
      }
      
      if (lastByte == MIDIMessageSysExEnd) {
        // End of a SysEx message.
        [client.delegate MIDIClient:client receivedData:client.sysExBuffer];
        client.sysExBuffer = nil;
      }
      
      packet = MIDIPacketNext(packet);
    }
  }
}

@implementation MIDIClient {
  NSString *_name;
  MIDIClientRef _client;
  MIDIPortRef _input;
  MIDIPortRef _output;
}

- (instancetype)initWithName:(NSString *)name error:(NSError **)error {
  if ((self = [super init])) {
    _name = name;  // Hold onto the name because MIDIClientCreate() doesn't retain it.
    OSStatus result = MIDIClientCreate((__bridge CFStringRef)name,
                                       midiStateChanged,
                                       (__bridge void *)self,
                                       &_client);
    if (result != noErr) {
      if (error) {
        *error = [NSError errorWithDomain:NSOSStatusErrorDomain code:result userInfo:nil];
      }
      self = nil;
    }
  }
  return self;
}

- (void)dealloc {
  MIDIClientDispose(_client);  // Automatically disposes of the ports too.
}

- (BOOL)connectToSource:(MIDISource *)source error:(NSError **)error {
  OSStatus result = noErr;
  if (!_input) {  // Lazily create the input port.
    result = MIDIInputPortCreate(_client,
                                 (__bridge CFStringRef)_name,
                                 midiRead,
                                 (__bridge void *)self,
                                 &_input);
    if (result != noErr) {
      if (error) {
        *error = [NSError errorWithDomain:NSOSStatusErrorDomain code:result userInfo:nil];
      }
      return NO;
    }
  }
  
  // Connect the source to the port.
  result = MIDIPortConnectSource(_input, source.endpoint, (__bridge void *)self);
  if (result != noErr) {
    if (error) {
      *error = [NSError errorWithDomain:NSOSStatusErrorDomain code:result userInfo:nil];
    }
    return NO;
  }
  
  self.source = source;
  return YES;
}

- (BOOL)connectToDestination:(MIDIDestination *)destination error:(NSError **)error {
  if (!_output) {  // Lazily create the output port.
    OSStatus result = MIDIOutputPortCreate(_client,
                                           (__bridge CFStringRef)_name,
                                           &_output);
    if (result != noErr) {
      if (error) {
        *error = [NSError errorWithDomain:NSOSStatusErrorDomain code:result userInfo:nil];
      }
      return NO;
    }
  }
  
  self.destination = destination;
  return YES;
}

- (BOOL)sendData:(NSData *)data error:(NSError **)error {
  if (data.length > sizeof(((MIDIPacket *)0)->data)) {
    // TODO(pquinn): Dynamically allocate a buffer. 
    if (error) {
      *error = [NSError errorWithDomain:MIDIClientErrorDomain
                                   code:0
                               userInfo:@{NSLocalizedDescriptionKey:
                                            @"Too much data for a basic MIDIPacket."}];
    }
    return NO;
  }
  
  MIDIPacketList packetList;
  MIDIPacket *packet = MIDIPacketListInit(&packetList);
  packet = MIDIPacketListAdd(&packetList, sizeof(packetList), packet, 0, data.length, data.bytes);
  if (!packet) {
    if (error) {
      *error = [NSError errorWithDomain:MIDIClientErrorDomain
                                   code:0
                               userInfo:@{NSLocalizedDescriptionKey:
                                            @"Packet too large for buffer."}];
    }
    return NO;
  }
  
  OSStatus result = MIDISend(_output, self.destination.endpoint, &packetList);
  if (result != noErr) {
    if (error) {
      *error = [NSError errorWithDomain:NSOSStatusErrorDomain code:result userInfo:nil];
    }
    return NO;
  }
  return YES;
}

- (BOOL)attachedToDevice:(MIDIDeviceRef)device {
  MIDIDeviceRef sourceDevice = 0, destinationDevice = 0;
  MIDIEntityGetDevice(self.source.endpoint, &sourceDevice);
  MIDIEntityGetDevice(self.destination.endpoint, &destinationDevice);
  
  SInt32 sourceID = 0, destinationID = 0, deviceID = 0;
  MIDIObjectGetIntegerProperty(sourceDevice, kMIDIPropertyUniqueID, &sourceID);
  MIDIObjectGetIntegerProperty(destinationDevice, kMIDIPropertyUniqueID, &destinationID);
  MIDIObjectGetIntegerProperty(device, kMIDIPropertyUniqueID, &deviceID);

  return (deviceID == sourceID || deviceID == destinationID);
}
@end
