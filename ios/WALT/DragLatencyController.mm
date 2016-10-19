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

#import "DragLatencyController.h"

#import <dispatch/dispatch.h>
#import <math.h>
#import <numeric>
#import <vector>

#import "UIAlertView+Extensions.h"
#import "WALTAppDelegate.h"
#import "WALTClient.h"
#import "WALTLogger.h"
#import "WALTTouch.h"

static const NSTimeInterval kGoalpostFrequency = 0.55;  // TODO(pquinn): User-configurable settings.
static const NSUInteger kMinTouchEvents = 100;
static const NSUInteger kMinLaserEvents = 8;
static const char kWALTLaserTag = 'L';

@interface WALTLaserEvent : NSObject
@property (assign) NSTimeInterval t;
@property (assign) int value;
@end

@implementation WALTLaserEvent
@end

/** Linear interpolation between x0 and x1 at alpha. */
template <typename T>
static T Lerp(const T& x0, const T& x1, double alpha) {
  NSCAssert(alpha >= 0 && alpha <= 1, @"alpha must be between 0 and 1 (%f)", alpha);
  return ((1 - alpha) * x0) + (alpha * x1);
}

/** Linear interpolation of (xp, yp) at x. */
template <typename S, typename T>
static std::vector<T> Interpolate(const std::vector<S>& x,
                                  const std::vector<S>& xp,
                                  const std::vector<T>& yp) {
  NSCAssert(xp.size(), @"xp must contain at least one value.");
  NSCAssert(xp.size() == yp.size(), @"xp and yp must have matching lengths.");
  
  std::vector<T> y;
  y.reserve(x.size());
  
  size_t i = 0;  // Index into x.
  
  for (; i < x.size() && x[i] < xp.front(); ++i) {
    y.push_back(yp.front());  // Pad out y with yp.front() for x values before xp.front().
  }
  
  size_t ip = 0;  // Index into xp/yp.
  
  for (; ip < xp.size() && i < x.size(); ++i) {
    while (ip < xp.size() && xp[ip] <= x[i]) {  // Find an xp[ip] greater than x[i].
      ++ip;
    }
    if (ip >= xp.size()) {
      break;  // Ran out of values.
    }

    const double alpha = (x[i] - xp[ip - 1]) / static_cast<double>(xp[ip] - xp[ip - 1]);
    y.push_back(Lerp(yp[ip - 1], yp[ip], alpha));
  }
  
  for (; i < x.size(); ++i) {
    y.push_back(yp.back());  // Pad out y with yp.back() for values after xp.back().
  }
  
  return y;
}

/** Extracts the values of y where the corresponding value in x is equal to value. */
template <typename S, typename T>
static std::vector<S> Extract(const std::vector<T>& x, const std::vector<S>& y, const T& value) {
  NSCAssert(x.size() == y.size(), @"x and y must have matching lengths.");
  std::vector<S> extracted;
  
  for (size_t i = 0; i < x.size(); ++i) {
    if (x[i] == value) {
      extracted.push_back(y[i]);
    }
  }
  
  return extracted;
}

/** Returns the standard deviation of the values in x. */
template <typename T>
static T StandardDeviation(const std::vector<T>& x) {
  NSCAssert(x.size() > 0, @"x must have at least one value.");
  const T sum = std::accumulate(x.begin(), x.end(), T{});
  const T mean = sum / x.size();
  const T ss = std::accumulate(x.begin(), x.end(), T{}, ^(T accum, T value){
      return accum + ((value - mean) * (value - mean));
  });
  return sqrt(ss / (x.size() - 1));
}

/** Returns the index of the smallest value in x. */
template <typename T>
static size_t ArgMin(const std::vector<T>& x) {
  NSCAssert(x.size() > 0, @"x must have at least one value.");
  size_t imin = 0;
  for (size_t i = 1; i < x.size(); ++i) {
    if (x[i] < x[imin]) {
      imin = i;
    }
  }
  return imin;
}

/**
 * Finds a positive time value that shifting laserTs by will minimise the standard deviation of
 * interpolated touchYs.
 */
static NSTimeInterval FindBestShift(const std::vector<NSTimeInterval>& laserTs,
                                    const std::vector<NSTimeInterval>& touchTs,
                                    const std::vector<CGFloat>& touchYs) {
  NSCAssert(laserTs.size() > 0, @"laserTs must have at least one value.");
  NSCAssert(touchTs.size() == touchYs.size(), @"touchTs and touchYs must have matching lengths.");
  
  const NSTimeInterval kSearchCoverage = 0.15;
  const int kSteps = 1500;
  const NSTimeInterval kShiftStep = kSearchCoverage / kSteps;
  
  std::vector<NSTimeInterval> deviations;
  deviations.reserve(kSteps);
  
  std::vector<NSTimeInterval> ts(laserTs.size());
  for (int i = 0; i < kSteps; ++i) {
    for (size_t j = 0; j < laserTs.size(); ++j) {
      ts[j] = laserTs[j] + (kShiftStep * i);
    }
    
    std::vector<CGFloat> laserYs = Interpolate(ts, touchTs, touchYs);
    deviations.push_back(StandardDeviation(laserYs));
  }
  
  return ArgMin(deviations) * kShiftStep;
}

@interface DragLatencyController ()
- (void)updateCountDisplay;
- (void)processEvent:(UIEvent *)event;
- (void)receiveTriggers:(id)context;
- (void)stopReceiver;
@end

@implementation DragLatencyController {
  WALTClient *_client;
  WALTLogger *_logger;

  NSMutableArray<WALTTouch *> *_touchEvents;
  NSMutableArray<WALTLaserEvent *> *_laserEvents;
  
  NSThread *_triggerReceiver;
  dispatch_semaphore_t _receiverComplete;
}

- (void)dealloc {
  [self stopReceiver];
}

- (void)viewDidLoad {
  [super viewDidLoad];
  
  _client = ((WALTAppDelegate *)[UIApplication sharedApplication].delegate).client;
  _logger = [WALTLogger sessionLogger];
}

- (void)viewWillAppear:(BOOL)animated {
  [super viewWillAppear:animated];

  [self updateCountDisplay];
  
  [_logger appendString:@"DRAGLATENCY\n"];
}

- (void)touchesBegan:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
  [self processEvent:event];
}

- (void)touchesMoved:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
  [self processEvent:event];
}

- (void)touchesEnded:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
  [self processEvent:event];
}

- (void)processEvent:(UIEvent *)event {
  // TODO(pquinn): Pull out coalesced touches.
  
  WALTTouch *touch = [[WALTTouch alloc] initWithEvent:event];
  [_touchEvents addObject:touch];
  [_logger appendFormat:@"TOUCH\t%.3f\t%.2f\t%.2f\n",
      touch.kernelTime, touch.location.x, touch.location.y];
  [self updateCountDisplay];
}

- (void)updateCountDisplay {
  NSString *counts = [NSString stringWithFormat:@"N ✛ %lu ⇄ %lu",
                         (unsigned long)_laserEvents.count, (unsigned long)_touchEvents.count];
  self.countLabel.text = counts;
}

- (IBAction)start:(id)sender {
  [self reset:sender];
  
  self.goalpostView.hidden = NO;
  self.statusLabel.text = @"";
  
  [UIView beginAnimations:@"Goalpost" context:NULL];
  [UIView setAnimationDuration:kGoalpostFrequency];
  [UIView setAnimationBeginsFromCurrentState:NO];
  [UIView setAnimationRepeatCount:FLT_MAX];
  [UIView setAnimationRepeatAutoreverses:YES];
  
  self.goalpostView.transform =
      CGAffineTransformMakeTranslation(0.0, -CGRectGetHeight(self.view.frame) + 300);
  
  [UIView commitAnimations];
  
  _receiverComplete = dispatch_semaphore_create(0);
  _triggerReceiver = [[NSThread alloc] initWithTarget:self
                                             selector:@selector(receiveTriggers:)
                                               object:nil];
  [_triggerReceiver start];
}

- (IBAction)reset:(id)sender {
  [self stopReceiver];
  
  self.goalpostView.transform = CGAffineTransformMakeTranslation(0.0, 0.0);
  self.goalpostView.hidden = YES;
  
  _touchEvents = [[NSMutableArray<WALTTouch *> alloc] init];
  _laserEvents = [[NSMutableArray<WALTLaserEvent *> alloc] init];
  
  [self updateCountDisplay];
  
  NSError *error = nil;
  if (![_client syncClocksWithError:&error]) {
    UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Connection Error" error:error];
    [alert show];
  }
  
  [_logger appendString:@"RESET\n"];
}

- (void)receiveTriggers:(id)context {
  // Turn on laser change notifications.
  NSError *error = nil;
  if (![_client sendCommand:WALTLaserOnCommand error:&error]) {
    UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Connection Error" error:error];
    [alert show];
    dispatch_semaphore_signal(_receiverComplete);
    return;
  }
  
  NSData *response = [_client readResponseWithTimeout:kWALTReadTimeout];
  if (![_client checkResponse:response forCommand:WALTLaserOnCommand]) {
    UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Response Error"
                                                    message:@"Failed to start laser probe."
                                                   delegate:nil
                                          cancelButtonTitle:@"Dismiss"
                                          otherButtonTitles:nil];
    [alert show];
    dispatch_semaphore_signal(_receiverComplete);
    return;
  }

  while (!NSThread.currentThread.isCancelled) {
    WALTTrigger response = [_client readTriggerWithTimeout:kWALTReadTimeout];
    if (response.tag == kWALTLaserTag) {
      WALTLaserEvent *event = [[WALTLaserEvent alloc] init];
      event.t = response.t;
      event.value = response.value;
      [_laserEvents addObject:event];
      [_logger appendFormat:@"LASER\t%.3f\t%d\n", event.t, event.value];
    } else if (response.tag != '\0') {  // Don't fail for timeout errors.
      UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"WALT Response Error"
                                                      message:@"Failed to read laser probe."
                                                     delegate:nil
                                            cancelButtonTitle:@"Dismiss"
                                            otherButtonTitles:nil];
      [alert show];
    }
  }
  
  // Turn off laser change notifications.
  [_client sendCommand:WALTLaserOffCommand error:nil];
  [_client readResponseWithTimeout:kWALTReadTimeout];

  dispatch_semaphore_signal(_receiverComplete);
}

- (void)stopReceiver {
  // TODO(pquinn): This will deadlock if called in rapid succession -- there is a small delay
  //               between dispatch_semaphore_signal() and -[NSThread isExecuting] changing.
  //               Unfortunately, NSThread is not joinable...
  if (_triggerReceiver.isExecuting) {
    [_triggerReceiver cancel];
    dispatch_semaphore_wait(_receiverComplete, DISPATCH_TIME_FOREVER);
  }
}

- (IBAction)computeStatistics:(id)sender {
  if (_touchEvents.count < kMinTouchEvents) {
    self.statusLabel.text =
        [NSString stringWithFormat:@"Too few touch events (%lu/%lu).",
          (unsigned long)_touchEvents.count, (unsigned long)kMinTouchEvents];
    [self reset:sender];
    return;
  }
  
  // Timestamps are reset to be relative to t0 to make the output easier to read.
  const NSTimeInterval t0 = _touchEvents.firstObject.kernelTime;
  const NSTimeInterval tF = _touchEvents.lastObject.kernelTime;
  
  std::vector<NSTimeInterval> ft(_touchEvents.count);
  std::vector<CGFloat> fy(_touchEvents.count);
  for (NSUInteger i = 0; i < _touchEvents.count; ++i) {
    ft[i] = _touchEvents[i].kernelTime - t0;
    fy[i] = _touchEvents[i].location.y;
  }
  
  // Remove laser events that have a timestamp outside [t0, tF].
  [_laserEvents filterUsingPredicate:[NSPredicate predicateWithBlock:
      ^BOOL(WALTLaserEvent *evaluatedObject, NSDictionary<NSString *, id> *bindings) {
        return evaluatedObject.t >= t0 && evaluatedObject.t <= tF;
  }]];

  if (_laserEvents.count < kMinLaserEvents) {
    self.statusLabel.text =
        [NSString stringWithFormat:@"Too few laser events (%lu/%lu).",
          (unsigned long)_laserEvents.count, (unsigned long)kMinLaserEvents];
    [self reset:sender];
    return;
  }
  
  if (_laserEvents.firstObject.value != 0) {
    self.statusLabel.text = @"First laser crossing was not into the beam.";
    [self reset:sender];
    return;
  }
  
  std::vector<NSTimeInterval> lt(_laserEvents.count);
  std::vector<int> lv(_laserEvents.count);
  for (NSUInteger i = 0; i < _laserEvents.count; ++i) {
    lt[i] = _laserEvents[i].t - t0;
    lv[i] = _laserEvents[i].value;
  }
  
  // Calculate interpolated touch y positions at each laser event.
  std::vector<CGFloat> ly = Interpolate(lt, ft, fy);
  
  // Labels for each laser event to denote those above/below the beam.
  // The actual side is irrelevant, but events on the same side should have the same label. The
  // vector will look like [0, 1, 1, 0, 0, 1, 1, 0, 0, ...].
  std::vector<int> sideLabels(lt.size());
  for (size_t i = 0; i < lt.size(); ++i) {
    sideLabels[i] = ((i + 1) / 2) % 2;
  }
  
  NSTimeInterval averageBestShift = 0;
  for (int side = 0; side < 2; ++side) {
    std::vector<NSTimeInterval> lts = Extract(sideLabels, lt, side);
    NSTimeInterval bestShift = FindBestShift(lts, ft, fy);
    averageBestShift += bestShift / 2;
  }
  
  self.statusLabel.text = [NSString stringWithFormat:@"%.3f s", averageBestShift];
  
  [self reset:sender];
}
@end
