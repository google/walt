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

#import "TapLatencyController.h"

#import "NSArray+Extensions.h"
#import "UIAlertView+Extensions.h"
#import "WALTAppDelegate.h"
#import "WALTClient.h"
#import "WALTLogger.h"
#import "WALTTouch.h"

@interface TapLatencyController ()
- (void)updateCountDisplay;
- (void)processEvent:(UIEvent *)event;
- (void)appendToLogView:(NSString *)string;
- (void)computeStatisticsForPhase:(UITouchPhase)phase;
@end

@implementation TapLatencyController {
  WALTClient *_client;
  WALTLogger *_logger;
  
  // Statistics
  unsigned int _downCount;
  unsigned int _downCountRecorded;
  unsigned int _upCount;
  unsigned int _upCountRecorded;
  
  NSMutableArray<WALTTouch *> *_touches;
}

- (void)viewDidLoad {
  [super viewDidLoad];
  
  self.logView.selectable = YES;
  self.logView.text = [NSString string];
  self.logView.selectable = NO;
  
  _logger = [WALTLogger sessionLogger];
  _client = ((WALTAppDelegate *)[UIApplication sharedApplication].delegate).client;
}

- (void)viewWillAppear:(BOOL)animated {
  [super viewWillAppear:animated];
  
  [_logger appendString:@"TAPLATENCY\n"];
  [self reset:nil];
}

- (void)touchesBegan:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
  [self processEvent:event];
  [self updateCountDisplay];
}

- (void)touchesEnded:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
  [self processEvent:event];
  [self updateCountDisplay];
}

- (void)updateCountDisplay {
  NSString *counts = [NSString stringWithFormat:@"N ↓%u (%u) ↑%u (%u)",
                      _downCountRecorded, _downCount, _upCountRecorded, _upCount];
  self.countLabel.text = counts;
}

- (void)processEvent:(UIEvent *)event {
  // TODO(pquinn): Pick first/last coalesced touch?
  
  NSTimeInterval kernelTime = event.timestamp;
  NSTimeInterval callbackTime = _client.currentTime;
  
  NSError *error = nil;
  NSTimeInterval physicalTime = [_client lastShockTimeWithError:&error];
  if (physicalTime == -1) {
    UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Connection Error" error:error];
    [alert show];
    return;
  }
  
  WALTTouch *touch = [[WALTTouch alloc] initWithEvent:event];
  touch.callbackTime = callbackTime;
  touch.physicalTime = physicalTime;
  
  NSString *actionString = nil;
  if (touch.phase == UITouchPhaseBegan) {
    _downCount += 1;
    actionString = @"ACTION_DOWN";
  } else {
    _upCount += 1;
    actionString = @"ACTION_UP";
  }
  
  if (physicalTime == 0) {
    [_logger appendFormat:@"%@\tX\tno shock\n", actionString];
    [self appendToLogView:[NSString stringWithFormat:@"%@: No shock detected\n", actionString]];
    return;
  }
  
  NSTimeInterval physicalToKernel = kernelTime - physicalTime;
  NSTimeInterval kernelToCallback = callbackTime - kernelTime;
  
  if (physicalToKernel < 0 || physicalToKernel > 0.2) {
    [_logger appendFormat:@"%@\tX\tbogus kernelTime\t%f\n", actionString, physicalToKernel];
    [self appendToLogView:
        [NSString stringWithFormat:@"%@: Bogus P → K: %.3f s\n", actionString, physicalToKernel]];
    return;
  }
  
  [_logger appendFormat:@"%@\tO\t%f\t%f\t%f\n",
      actionString, _client.baseTime, physicalToKernel, kernelToCallback];
  
  [self appendToLogView:
      [NSString stringWithFormat:@"%@: P → K: %.3f s; K → C: %.3f s\n",
        actionString, physicalToKernel, kernelToCallback]];

  [_touches addObject:touch];
  if (touch.phase == UITouchPhaseBegan) {
    _downCountRecorded += 1;
  } else {
    _upCountRecorded += 1;
  }
}

- (IBAction)reset:(id)sender {
  _downCount = 0;
  _downCountRecorded = 0;
  _upCount = 0;
  _upCountRecorded = 0;
  [self updateCountDisplay];
  
  _touches = [[NSMutableArray<WALTTouch *> alloc] init];
  
  NSError *error = nil;
  if (![_client syncClocksWithError:&error]) {
    UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Connection Error" error:error];
    [alert show];
  }

  [_logger appendString:@"RESET\n"];
  [self appendToLogView:@"===========================================\n"];
}

- (IBAction)computeStatistics:(id)sender {
  [self appendToLogView:@"-------------------------------------------\n"];
  [self appendToLogView:@"Medians:\n"];
  [self computeStatisticsForPhase:UITouchPhaseBegan];
  [self computeStatisticsForPhase:UITouchPhaseEnded];
  
  [self reset:sender];
}

- (void)computeStatisticsForPhase:(UITouchPhase)phase {
  NSMutableArray<NSNumber *> *p2k = [[NSMutableArray<NSNumber *> alloc] init];
  NSMutableArray<NSNumber *> *k2c = [[NSMutableArray<NSNumber *> alloc] init];
  
  for (WALTTouch *touch in _touches) {
    if (touch.phase != phase) {
      continue;
    }
    
    [p2k addObject:[NSNumber numberWithDouble:touch.kernelTime - touch.physicalTime]];
    [k2c addObject:[NSNumber numberWithDouble:touch.callbackTime - touch.kernelTime]];
  }
  
  NSNumber *p2kMedian = [p2k medianValue];
  NSNumber *k2cMedian = [k2c medianValue];
  
  NSString *actionString = (phase == UITouchPhaseBegan ? @"ACTION_DOWN" : @"ACTION_UP");
  [self appendToLogView:
      [NSString stringWithFormat:@"%@: P → K: %.3f s; K → C: %.3f s\n",
        actionString, p2kMedian.doubleValue, k2cMedian.doubleValue]];
}

- (void)appendToLogView:(NSString*)string {
  self.logView.selectable = YES;
  self.logView.text = [self.logView.text stringByAppendingString:string];
  [self.logView scrollRangeToVisible:NSMakeRange(self.logView.text.length - 2, 1)];
  self.logView.selectable = NO;
}
@end
