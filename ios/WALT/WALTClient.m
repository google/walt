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

#import "WALTClient.h"

#include <ctype.h>
#include <dispatch/dispatch.h>
#include <mach/clock.h>
#include <mach/mach.h>
#include <mach/mach_host.h>
#include <stdlib.h>
#include <time.h>

#import "MIDIEndpoint.h"
#import "MIDIMessage.h"

NSString * const WALTClientErrorDomain = @"WALTClientErrorDomain";

static NSString * const kWALTVersion = @"v 4";

static const MIDIChannel kWALTChannel = 1;
static const MIDIByte kWALTSerialOverMIDIProgram = 1;
static const MIDIMessageType kWALTCommandType = MIDIMessageChannelPressure;

const NSTimeInterval kWALTReadTimeout = 0.2;
static const NSTimeInterval kWALTDuplicateTimeout = 0.01;

static const int kWALTSyncIterations = 7;
#define kWALTSyncDigitMax 9  // #define to avoid variable length array warnings.

/** Similar to atoll(), but only reads a maximum of n characters from s. */
static unsigned long long antoull(const char *s, size_t n) {
  unsigned long long result = 0;
  while (s && n-- && *s && isdigit(*s)) {
    result = result * 10 + (*s - '0');
    ++s;
  }
  return result;
}

/** Converts a mach_timespec_t to its equivalent number of microseconds. */
static int64_t TimespecToMicroseconds(const mach_timespec_t ts) {
  return ((int64_t)ts.tv_sec) * USEC_PER_SEC + ts.tv_nsec / NSEC_PER_USEC;
}

/** Returns the current time (in microseconds) on a clock. */
static int64_t CurrentTime(clock_serv_t clock) {
  mach_timespec_t time = {0};
  clock_get_time(clock, &time);
  return TimespecToMicroseconds(time);
}

/** Sleeps the current thread for us microseconds. */
static void Sleep(int64_t us) {
  const struct timespec ts = {
    .tv_sec = (long)(us / USEC_PER_SEC),
    .tv_nsec = (us % USEC_PER_SEC) * NSEC_PER_USEC,
  };
  nanosleep(&ts, NULL);
}

@interface WALTClient ()
@property (readwrite, nonatomic, getter=isConnected) BOOL connected;

- (void)drainResponseQueue;
- (BOOL)improveSyncBoundsWithError:(NSError **)error;
- (BOOL)improveMinBoundWithError:(NSError **)error;
- (BOOL)improveMaxBoundWithError:(NSError **)error;
- (BOOL)readRemoteTimestamps:(uint64_t[kWALTSyncDigitMax])times error:(NSError **)error;
- (WALTTrigger)readTrigger:(NSData *)response;
@end

@implementation WALTClient {
  MIDIClient *_client;
  
  // Responses from the MIDIClient are queued up here with a signal to the semaphore.
  NSMutableArray<NSData *> *_responseQueue;  // TODO(pquinn): Lock-free circular buffer?
  dispatch_semaphore_t _responseSemaphore;
  
  BOOL _syncCompleted;
  
  clock_serv_t _clock;
  
  NSData *_lastData;
  NSTimeInterval _lastDataTimestamp;
  
  struct {
    // All microseconds.
    int64_t base;
    int64_t minError;
    int64_t maxError;
  } _sync;
}

- (instancetype)initWithError:(NSError **)error {
  if ((self = [super init])) {
    _responseQueue = [[NSMutableArray<NSData *> alloc] init];
    _responseSemaphore = dispatch_semaphore_create(0);
    
    // NB: It's important that this is the same clock used as the base for UIEvent's -timestamp.
    kern_return_t result = host_get_clock_service(mach_host_self(), SYSTEM_CLOCK, &_clock);
    
    if (result != KERN_SUCCESS || ![self checkConnectionWithError:error]) {
      self = nil;
    }
  }
  return self;
}

- (void)dealloc {
  [self drainResponseQueue];
  mach_port_deallocate(mach_task_self(), _clock);
}

// Ensure only one KVO notification is sent when the connection state is changed.
+ (BOOL)automaticallyNotifiesObserversOfConnected {
  return NO;
}

- (void)setConnected:(BOOL)connected {
  if (_connected != connected) {
    [self willChangeValueForKey:@"connected"];
    _connected = connected;
    [self didChangeValueForKey:@"connected"];
  }
}

- (BOOL)checkConnectionWithError:(NSError **)error {
  if (_client.source.isOnline && _client.destination.isOnline && _syncCompleted) {
    self.connected = YES;
    return YES;  // Everything's fine.
  }
  
  _syncCompleted = NO;  // Reset the sync state.
  [self drainResponseQueue];
  
  // Create a new client.
  // This probably isn't strictly necessary, but solves some of the flakiness on iOS.
  _client.delegate = nil;
  _client = [[MIDIClient alloc] initWithName:@"WALT" error:error];
  _client.delegate = self;
  if (!_client) {
    self.connected = NO;
    return NO;
  }
 
  if (!_client.source.isOnline) {
    // Try to connect to the first available input source.
    // TODO(pquinn): Make this user-configurable.
    NSArray<MIDISource *> *sources = [MIDISource allSources];
    if (sources.count) {
      if (![_client connectToSource:sources.firstObject error:error]) {
        self.connected = NO;
        return NO;
      }
    }
  }
  
  if (!_client.destination.isOnline) {
    // Try to connect to the first available input source.
    // TODO(pquinn): Make this user-configurable.
    NSArray<MIDIDestination *> *destinations = [MIDIDestination allDestinations];
    if (destinations.count) {
      if (![_client connectToDestination:destinations.firstObject error:error]) {
        self.connected = NO;
        return NO;
      }
    }
    
    if (_client.destination.isOnline) {
      // Switch to Serial-over-MIDI mode.
      NSData *message = MIDIMessageCreateSimple1(MIDIMessageProgramChange,
                                                 kWALTChannel,
                                                 kWALTSerialOverMIDIProgram);
      if (![_client sendData:message error:error]) {
        self.connected = NO;
        return NO;
      }
      
      // Make sure it's using a known protocol version.
      message = MIDIMessageCreateSimple1(kWALTCommandType, kWALTChannel, WALTVersionCommand);
      if (![_client sendData:message error:error]) {
        self.connected = NO;
        return NO;
      }
      
      NSData *response = [self readResponseWithTimeout:kWALTReadTimeout];
      NSString *versionString = [[NSString alloc] initWithData:response
                                                      encoding:NSASCIIStringEncoding];
      if (![versionString isEqualToString:kWALTVersion]) {
        if (error) {
          *error = [NSError errorWithDomain:WALTClientErrorDomain
                                       code:0
                                   userInfo:@{NSLocalizedDescriptionKey:
                                                [@"Unknown WALT version: "
                                                  stringByAppendingString:versionString]}];
        }
        self.connected = NO;
        return NO;
      }
      
      if (![self syncClocksWithError:error]) {
        self.connected = NO;
        return NO;
      }
      
      _syncCompleted = YES;
    }
  }
  
  self.connected = (_client.source.isOnline && _client.destination.isOnline && _syncCompleted);
  return YES;
}

#pragma mark - Clock Synchronisation

- (BOOL)syncClocksWithError:(NSError **)error {
  _sync.base = CurrentTime(_clock);
  
  if (![self sendCommand:WALTZeroSyncCommand error:error]) {
    return NO;
  }
  
  NSData *data = [self readResponseWithTimeout:kWALTReadTimeout];
  if (![self checkResponse:data forCommand:WALTZeroSyncCommand]) {
    if (error) {
      *error = [NSError errorWithDomain:WALTClientErrorDomain
                                   code:0
                               userInfo:@{NSLocalizedDescriptionKey:
          [NSString stringWithFormat:@"Bad acknowledgement for WALTZeroSyncCommand: %@", data]}];
    }
    return NO;
  }

  _sync.maxError = CurrentTime(_clock) - _sync.base;
  _sync.minError = 0;
  
  for (int i = 0; i < kWALTSyncIterations; ++i) {
    if (![self improveSyncBoundsWithError:error]) {
      return NO;
    }
  }
  
  // Shift the time base so minError == 0
  _sync.base += _sync.minError;
  _sync.maxError -= _sync.minError;
  _sync.minError = 0;
  return YES;
}

- (BOOL)updateSyncBoundsWithError:(NSError **)error {
  // Reset the bounds to unrealistic values
  _sync.minError = -1e7;
  _sync.maxError =  1e7;
  
  for (int i = 0; i < kWALTSyncIterations; ++i) {
    if (![self improveSyncBoundsWithError:error]) {
      return NO;
    }
  }
  
  return YES;
}

- (int64_t)minError {
  return _sync.minError;
}

- (int64_t)maxError {
  return _sync.maxError;
}

- (BOOL)improveSyncBoundsWithError:(NSError **)error {
  return ([self improveMinBoundWithError:error] && [self improveMaxBoundWithError:error]);
}

- (BOOL)improveMinBoundWithError:(NSError **)error {
  if (![self sendCommand:WALTResetCommand error:error]) {
    return NO;
  }
  
  NSData *data = [self readResponseWithTimeout:kWALTReadTimeout];
  if (![self checkResponse:data forCommand:WALTResetCommand]) {
    if (error) {
      *error = [NSError errorWithDomain:WALTClientErrorDomain
                                   code:0
                               userInfo:@{NSLocalizedDescriptionKey:
          [NSString stringWithFormat:@"Bad acknowledgement for WALTResetCommand: %@", data]}];
    }
    return NO;
  }

  const uint64_t kMaxSleepTime = 700; // µs
  const uint64_t kMinSleepTime = 70;  // µs
  const uint64_t kSleepTimeDivider = 10;
  
  uint64_t sleepTime = (_sync.maxError - _sync.minError) / kSleepTimeDivider;
  if (sleepTime > kMaxSleepTime) { sleepTime = kMaxSleepTime; }
  if (sleepTime < kMinSleepTime) { sleepTime = kMinSleepTime; }
  
  struct {
    uint64_t local[kWALTSyncDigitMax];
    uint64_t remote[kWALTSyncDigitMax];
  } digitTimes = {0};
  
  // Send the digits 1 through 9 and record the times they were sent in digitTimes.local.
  for (int i = 0; i < kWALTSyncDigitMax; ++i) {
    digitTimes.local[i] = CurrentTime(_clock) - _sync.base;
    
    char c = '1' + i;
    if (![self sendCommand:c error:error]) {
      if (error) {
        *error = [NSError errorWithDomain:WALTClientErrorDomain
                                     code:0
                                 userInfo:@{NSLocalizedDescriptionKey:
            [NSString stringWithFormat:@"Error sending digit %d", i + 1],
                                            NSUnderlyingErrorKey: *error}];
      }
      return NO;
    }
    // Sleep between digits
    Sleep(sleepTime);
  }
  
  if (![self readRemoteTimestamps:digitTimes.remote error:error]) {
    return NO;
  }
  
  // Adjust minError to be the largest delta between local and remote.
  for (int i = 0; i < kWALTSyncDigitMax; ++i) {
    int64_t delta = digitTimes.local[i] - digitTimes.remote[i];
    if (digitTimes.local[i] != 0 && digitTimes.remote[i] != 0 && delta > _sync.minError) {
      _sync.minError = delta;
    }
  }
  return YES;
}

- (BOOL)improveMaxBoundWithError:(NSError **)error {
  struct {
    uint64_t local[kWALTSyncDigitMax];
    uint64_t remote[kWALTSyncDigitMax];
  } digitTimes = {0};
  
  // Ask the WALT to send the digits 1 through 9, and record the times they are received in
  // digitTimes.local.
  if (![self sendCommand:WALTSendSyncCommand error:error]) {
    return NO;
  }

  for (int i = 0; i < kWALTSyncDigitMax; ++i) {
    NSData *data = [self readResponseWithTimeout:kWALTReadTimeout];
    if (data.length != 1) {
      if (error) {
        *error = [NSError errorWithDomain:WALTClientErrorDomain
                                     code:0
                                 userInfo:@{NSLocalizedDescriptionKey:
            [NSString stringWithFormat:@"Error receiving digit %d: %@", i + 1, data]}];
      }
      return NO;
    }
    
    char c = ((const char *)data.bytes)[0];
    if (!isdigit(c)) {
      if (error) {
        *error = [NSError errorWithDomain:WALTClientErrorDomain
                                     code:0
                                 userInfo:@{NSLocalizedDescriptionKey:
            [NSString stringWithFormat:@"Error parsing digit response: %c", c]}];
      }
      return NO;
    }
    
    int digit = c - '0';
    digitTimes.local[digit - 1] = CurrentTime(_clock) - _sync.base;
  }
  
  if (![self readRemoteTimestamps:digitTimes.remote error:error]) {
    return NO;
  }
  
  // Adjust maxError to be the smallest delta between local and remote
  for (int i = 0; i < kWALTSyncDigitMax; ++i) {
    int64_t delta = digitTimes.local[i] - digitTimes.remote[i];
    if (digitTimes.local[i] != 0 && digitTimes.remote[i] != 0 && delta < _sync.maxError) {
      _sync.maxError = delta;
    }
  }
  return YES;
}

- (BOOL)readRemoteTimestamps:(uint64_t [9])times error:(NSError **)error {
  for (int i = 0; i < kWALTSyncDigitMax; ++i) {
    // Ask the WALT for each digit's recorded timestamp
    if (![self sendCommand:WALTReadoutSyncCommand error:error]) {
      return NO;
    }
    
    NSData *data = [self readResponseWithTimeout:kWALTReadTimeout];
    if (data.length < 3) {
      if (error) {
        *error = [NSError errorWithDomain:WALTClientErrorDomain
                                     code:0
                                 userInfo:@{NSLocalizedDescriptionKey:
            [NSString stringWithFormat:@"Error receiving sync digit %d: %@", i + 1, data]}];
      }
      return NO;
    }
    
    // The reply data is formatted as n:xxxx, where n is a digit between 1 and 9, and xxxx
    // is a microsecond timestamp.
    int digit = (int)antoull(data.bytes, 1);
    uint64_t timestamp = antoull(((const char *)data.bytes) + 2, data.length - 2);
    
    if (digit != (i + 1) || timestamp == 0) {
      if (error) {
        *error = [NSError errorWithDomain:WALTClientErrorDomain
                                     code:0
                                 userInfo:@{NSLocalizedDescriptionKey:
            [NSString stringWithFormat:@"Error parsing remote time response for %d: %@", i, data]}];
      }
      return NO;
    }
    times[digit - 1] = timestamp;
  }
  return YES;
}

#pragma mark - MIDIClient Delegate

// TODO(pquinn): Errors from these callbacks aren't propoagated anywhere.

- (void)MIDIClientEndpointAdded:(MIDIClient *)client {
  [self performSelectorOnMainThread:@selector(checkConnectionWithError:)
                         withObject:nil
                      waitUntilDone:NO];
}

- (void)MIDIClientEndpointRemoved:(MIDIClient *)client {
  [self performSelectorOnMainThread:@selector(checkConnectionWithError:)
                         withObject:nil
                      waitUntilDone:NO];
}

- (void)MIDIClientConfigurationChanged:(MIDIClient *)client {
  [self performSelectorOnMainThread:@selector(checkConnectionWithError:)
                         withObject:nil
                      waitUntilDone:NO];
}

- (void)MIDIClient:(MIDIClient *)client receivedError:(NSError *)error {
  // TODO(pquinn): What's the scope of these errors?
  NSLog(@"WALTClient received unhandled error: %@", error);
}

- (void)MIDIClient:(MIDIClient *)client receivedData:(NSData *)message {
  NSData *body = MIDIMessageBody(message);
  @synchronized (_responseQueue) {
    // Sometimes a message will be received twice in quick succession. It's not clear where the bug
    // is (the WALT, CoreMIDI, or this application), and it cannot be reliably reproduced. As a
    // hack, simply ignore messages that appear to be duplicates and arrive within
    // kWALTDuplicateTimeout seconds.
    if (self.currentTime - _lastDataTimestamp <= kWALTDuplicateTimeout &&
        [body isEqualToData:_lastData]) {
      NSLog(@"Ignoring duplicate response within kWALTDuplicateTimeout: %@", message);
      return;
    }
    
    [_responseQueue addObject:body];
    _lastData = body;
    _lastDataTimestamp = self.currentTime;
  }
  dispatch_semaphore_signal(_responseSemaphore);
}

#pragma mark - Send/Receive

- (void)drainResponseQueue {
  @synchronized (_responseQueue) {
    // Drain out any stale responses or the semaphore destructor will assert.
    while (_responseQueue.count) {
      dispatch_semaphore_wait(_responseSemaphore, DISPATCH_TIME_FOREVER);
      [_responseQueue removeObjectAtIndex:0];
    }
  }
}

- (NSData *)readResponseWithTimeout:(NSTimeInterval)timeout {
  if (dispatch_semaphore_wait(_responseSemaphore,
                              dispatch_time(DISPATCH_TIME_NOW, timeout * NSEC_PER_SEC))) {
    return nil;
  }
  
  @synchronized (_responseQueue) {
    NSAssert(_responseQueue.count > 0, @"_responseQueue is empty!");
    NSData *response = _responseQueue.firstObject;
    [_responseQueue removeObjectAtIndex:0];
    return response;
  }
}

- (BOOL)sendCommand:(WALTCommand)command error:(NSError **)error {
  NSData *message = MIDIMessageCreateSimple1(kWALTCommandType, kWALTChannel, command);
  [self drainResponseQueue];
  return [_client sendData:message error:error];
}

- (BOOL)checkResponse:(NSData *)response forCommand:(WALTCommand)command {
  const WALTCommand flipped = isupper(command) ? tolower(command) : toupper(command);
  if (response.length < 1 || ((const char *)response.bytes)[0] != flipped) {
    return NO;
  } else {
    return YES;
  }
}

#pragma mark - Specific Commands

- (NSTimeInterval)lastShockTimeWithError:(NSError **)error {
  if (![self sendCommand:WALTGShockCommand error:error]) {
    return -1;
  }
  
  NSData *response = [self readResponseWithTimeout:kWALTReadTimeout];
  if (!response) {
    if (error) {
      *error = [NSError errorWithDomain:WALTClientErrorDomain
                                   code:0
                               userInfo:@{NSLocalizedDescriptionKey:
                                            @"Error receiving shock response."}];
    }
    return -1;
  }
  
  uint64_t microseconds = antoull(response.bytes, response.length);
  return ((NSTimeInterval)microseconds + _sync.base) / USEC_PER_SEC;
}

- (WALTTrigger)readTrigger:(NSData *)response {
  NSString *responseString =
      [[NSString alloc] initWithData:response encoding:NSASCIIStringEncoding];
  NSArray<NSString *> *components = [responseString componentsSeparatedByString:@" "];
  
  WALTTrigger result = {0};
  
  if (components.count != 5 ||
      ![[components objectAtIndex:0] isEqualToString:@"G"] ||
      [components objectAtIndex:1].length != 1) {
    return result;
  }
  
  result.tag = [[components objectAtIndex:1] characterAtIndex:0];
  
  uint64_t microseconds = atoll([components objectAtIndex:2].UTF8String);
  result.t = ((NSTimeInterval)microseconds + _sync.base) / USEC_PER_SEC;
  result.value = (int)atoll([components objectAtIndex:3].UTF8String);
  result.count = (unsigned int)atoll([components objectAtIndex:4].UTF8String);
  return result;
}

- (WALTTrigger)readTriggerWithTimeout:(NSTimeInterval)timeout {
  return [self readTrigger:[self readResponseWithTimeout:timeout]];
}

#pragma mark - Time

- (NSTimeInterval)baseTime {
  return ((NSTimeInterval)_sync.base) / USEC_PER_SEC;
}

- (NSTimeInterval)currentTime {
  return ((NSTimeInterval)CurrentTime(_clock)) / USEC_PER_SEC;
}
@end
