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

#import "MIDIClient.h"
#import "MIDIMessage.h"

extern NSString * const WALTClientErrorDomain;

/** A reasonable timeout to use when reading from the WALT. */
extern const NSTimeInterval kWALTReadTimeout;

typedef NS_ENUM(MIDIByte, WALTCommand) {
  WALTDelayedPingCommand     = 'D',  // Ping with a delay
  WALTResetCommand           = 'F',  // Reset all vars
  WALTSendSyncCommand        = 'I',  // Send some digits for clock sync
  WALTPingCommand            = 'P',  // Ping with a single byte
  WALTVersionCommand         = 'V',  // Determine WALT's firmware version
  WALTReadoutSyncCommand     = 'R',  // Read out sync times
  WALTGShockCommand          = 'G',  // Send last shock time and watch for another
  WALTTimeCommand            = 'T',  // Current time
  WALTZeroSyncCommand        = 'Z',  // Initial zero
  WALTScreenOnCommand        = 'C',  // Send a message on screen color change
  WALTScreenOffCommand       = 'c',
  WALTSendLastScreenCommand  = 'E',  // Send info about last screen color change
  WALTBrightnessCurveCommand = 'U',  // Probe screen for brightness vs time curve
  WALTLaserOnCommand         = 'L',  // Send messages on state change of the laser
  WALTLaserOffCommand        = 'l',
  WALTSendLastLaserCommand   = 'J',
  WALTAudioCommand           = 'A',  // Start watching for signal on audio out line
  WALTBeepCommand            = 'B',  // Generate a tone into the mic and send timestamp
  WALTBeepStopCommand        = 'S',  // Stop generating tone
  WALTMIDICommand            = 'M',  // Start listening for a MIDI message
  WALTNoteCommand            = 'N',  // Generate a MIDI NoteOn message
};

typedef struct {
  char tag;
  NSTimeInterval t;
  int value;
  unsigned int count;
} WALTTrigger;

/**
 * A client for a WALT device.
 *
 * The client will automatically try to connect to any available WALT device, and monitor the system
 * for device connections/disconnections. Users should observe the "connected" key to be notified of
 * connection changes.
 *
 * Most commands produce a corresponding response from the WALT. The -sendCommand:error: method
 * should be used to send the command, and -readResponse used to collect the response.
 */
@interface WALTClient : NSObject <MIDIClientDelegate>
@property (readonly, nonatomic, getter=isConnected) BOOL connected;

/**
 * Returns the base time of the WALT device.
 *
 * The time value is an adjusted version of -currentTime.
 */
@property (readonly, nonatomic) NSTimeInterval baseTime;

/** Returns the number of seconds the system has been awake since it was last restarted. */
@property (readonly, nonatomic) NSTimeInterval currentTime;

/** Initialises the client and attempts to connect to any available WALT device. */
- (instancetype)initWithError:(NSError **)error;

/** Sends a command to the WALT. */
- (BOOL)sendCommand:(WALTCommand)command error:(NSError **)error;

/** Reads a response from the WALT, blocking up to timeout until one becomes available. */
- (NSData *)readResponseWithTimeout:(NSTimeInterval)timeout;

/**
 * Reads a trigger response from the WALT.
 *
 * If an error occurs, the trigger's tag will be '\0'.
 */
- (WALTTrigger)readTriggerWithTimeout:(NSTimeInterval)timeout;

/** Returns YES if the response data contains a valid acknowledgement for a command. */
- (BOOL)checkResponse:(NSData *)response forCommand:(WALTCommand)command;

/** Forces a complete clock synchronisation with the WALT. */
- (BOOL)syncClocksWithError:(NSError **)error;

/** Refreshes the min/max error synchronisation bounds. */
- (BOOL)updateSyncBoundsWithError:(NSError **)error;

@property (readonly, nonatomic) int64_t minError;
@property (readonly, nonatomic) int64_t maxError;

/**
 * Confirms the connection with the WALT (by setting -isConnected).
 *
 * Note that this method will only return NO if there is an error in the connection process. The
 * absence of a device is not such an error.
 */
- (BOOL)checkConnectionWithError:(NSError **)error;

/** Returns the time of the last shock detected by the WALT. */
- (NSTimeInterval)lastShockTimeWithError:(NSError **)error;
@end
