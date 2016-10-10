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

#import "ScreenResponseController.h"

#include <stdatomic.h>

#import "NSArray+Extensions.h"
#import "UIAlertView+Extensions.h"
#import "WALTAppDelegate.h"
#import "WALTClient.h"
#import "WALTLogger.h"

static const NSUInteger kMaxFlashes = 20;  // TODO(pquinn): Make this user-configurable.
static const NSTimeInterval kFlashingInterval = 0.1;
static const char kWALTScreenTag = 'S';

@interface ScreenResponseController ()
- (void)setFlashTimer;
- (void)flash:(NSTimer *)timer;
@end

@implementation ScreenResponseController {
  WALTClient *_client;
  WALTLogger *_logger;
  
  NSTimer *_flashTimer;
  NSOperationQueue *_readOperations;
  
  // Statistics
  NSUInteger _initiatedFlashes;
  NSUInteger _detectedFlashes;
  
  _Atomic NSTimeInterval _lastFlashTime;
  NSMutableArray<NSNumber *> *_deltas;
}

- (void)dealloc {
  [_readOperations cancelAllOperations];
  [_flashTimer invalidate];
}

- (void)viewDidLoad {
  [super viewDidLoad];

  _client = ((WALTAppDelegate *)[UIApplication sharedApplication].delegate).client;
  _logger = [WALTLogger sessionLogger];
}

- (void)viewWillAppear:(BOOL)animated {
  [super viewWillAppear:animated];
  
  [_logger appendString:@"SCREENRESPONSE\n"];
  [self reset:nil];
}

- (IBAction)start:(id)sender {
  [self reset:nil];
  
  // Clear the screen trigger on the WALT.
  NSError *error = nil;
  if (![_client sendCommand:WALTSendLastScreenCommand error:&error]) {
    UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Connection Error" error:error];
    [alert show];
    return;
  }
  
  WALTTrigger trigger = [_client readTriggerWithTimeout:kWALTReadTimeout];
  if (trigger.tag != kWALTScreenTag) {
    UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Response Error"
                                                    message:@"Failed to read last screen trigger."
                                                   delegate:nil
                                          cancelButtonTitle:@"Dismiss"
                                          otherButtonTitles:nil];
    [alert show];
    return;
  }
  
  // Create a queue for work blocks to read WALT trigger responses.
  _readOperations = [[NSOperationQueue alloc] init];
  _readOperations.maxConcurrentOperationCount = 1;
  
  // Start the flash timer and spawn a thread to check for responses.
  [self setFlashTimer];
}

- (void)setFlashTimer {
  _flashTimer = [NSTimer scheduledTimerWithTimeInterval:kFlashingInterval
                                                 target:self
                                               selector:@selector(flash:)
                                               userInfo:nil
                                                repeats:NO];
}

- (IBAction)computeStatistics:(id)sender {
  self.flasherView.hidden = YES;
  self.responseLabel.hidden = NO;
  
  NSMutableString *results = [[NSMutableString alloc] init];
  for (NSNumber *delta in _deltas) {
    [results appendFormat:@"%.3f s\n", delta.doubleValue];
  }
  
  [results appendFormat:@"Median: %.3f s\n", [_deltas medianValue].doubleValue];
  self.responseLabel.text = results;
}

- (IBAction)reset:(id)sender {
  _initiatedFlashes = 0;
  _detectedFlashes = 0;
  _deltas = [[NSMutableArray<NSNumber *> alloc] init];
  
  [_readOperations cancelAllOperations];
  [_flashTimer invalidate];
 
  self.flasherView.hidden = NO;
  self.flasherView.backgroundColor = [UIColor whiteColor];
  self.responseLabel.hidden = YES;
  
  NSError *error = nil;
  if (![_client syncClocksWithError:&error]) {
    UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Connection Error" error:error];
    [alert show];
  }
  
  [_logger appendString:@"RESET\n"];
}

- (void)flash:(NSTimer *)timer {
  if (_initiatedFlashes == 0) {
    // First flash.
    // Turn on brightness change notifications.
    NSError *error = nil;
    if (![_client sendCommand:WALTScreenOnCommand error:&error]) {
      UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Connection Error" error:error];
      [alert show];
      return;
    }
    
    NSData *response = [_client readResponseWithTimeout:kWALTReadTimeout];
    if (![_client checkResponse:response forCommand:WALTScreenOnCommand]) {
      UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Response Error"
                                                      message:@"Failed to start screen probe."
                                                     delegate:nil
                                            cancelButtonTitle:@"Dismiss"
                                            otherButtonTitles:nil];
      [alert show];
      return;
    }
  }
  
  if (_initiatedFlashes != kMaxFlashes) {
    // Swap the background colour and record the time.
    self.flasherView.backgroundColor =
        ([self.flasherView.backgroundColor isEqual:[UIColor blackColor]] ?
         [UIColor whiteColor] :
         [UIColor blackColor]);
    atomic_store(&_lastFlashTime, _client.currentTime);
    ++_initiatedFlashes;

    // Queue an operation to read the trigger.
    [_readOperations addOperationWithBlock:^{
      // NB: The timeout here should be much greater than the expected screen response time. 
      WALTTrigger response = [_client readTriggerWithTimeout:kWALTReadTimeout];
      if (response.tag == kWALTScreenTag) {
        ++_detectedFlashes;
        
        // Record the delta between the trigger and the flash time.
        NSTimeInterval lastFlash = atomic_load(&_lastFlashTime);
        NSTimeInterval delta = response.t - lastFlash;
        if (delta > 0) {  // Sanity check
          [_deltas addObject:[NSNumber numberWithDouble:delta]];
          [_logger appendFormat:@"O\t%f\n", delta];
        } else {
          [_logger appendFormat:@"X\tbogus delta\t%f\t%f\n", lastFlash, response.t];
        }
        
        // Queue up another flash.
        [self performSelectorOnMainThread:@selector(setFlashTimer)
                               withObject:nil
                            waitUntilDone:NO];
      } else {
        UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Response Error"
                                                        message:@"Failed to read screen probe."
                                                       delegate:nil
                                              cancelButtonTitle:@"Dismiss"
                                              otherButtonTitles:nil];
        [alert show];
      }
    }];
  }
  
  if (_initiatedFlashes == kMaxFlashes) {
    // Queue an operation (after the read trigger above) to turn off brightness notifications.
    [_readOperations addOperationWithBlock:^{
      [_client sendCommand:WALTScreenOffCommand error:nil];
      [_client readResponseWithTimeout:kWALTReadTimeout];
      [self performSelectorOnMainThread:@selector(computeStatistics:)
                             withObject:nil
                          waitUntilDone:NO];
    }];
  }
}
@end
